package com.aquamix.drawbot.automation

import com.aquamix.drawbot.AquamixDrawBot
import com.aquamix.drawbot.config.ModConfig
import com.aquamix.drawbot.input.BotInput
import com.aquamix.drawbot.input.InputOverrideHandler
import net.minecraft.client.MinecraftClient
import kotlin.math.atan2
import kotlin.math.sqrt

import com.aquamix.drawbot.pathing.PathFinder
import net.minecraft.util.math.BlockPos

/**
 * Контроллер полёта - использует InputOverrideHandler для управления
 * Движение применяется через AquamixInput mixin
 */
class FlightController {
    private var lastFlyCommand = 0L
    private var flyCommandCooldown = 2000L
    
    private var isFlying = false
    private var wasOnGround = true
    
    // Movement state detection
    enum class MovementState {
        IDLE, WALKING, RUNNING, FLYING, FALLING, ASCENDING
    }
    
    var currentState = MovementState.IDLE
        private set
    
    // Fall recovery
    private var lastSafePosition: net.minecraft.util.math.Vec3d? = null
    private var fallStartPosition: net.minecraft.util.math.Vec3d? = null
    private var isFalling = false
    private var fallStartTime = 0L
    private var lastVelocityY = 0.0
    
    /**
     * Детектирует текущее состояние движения игрока
     */
    fun detectMovementState(client: MinecraftClient): MovementState {
        val player = client.player ?: return MovementState.IDLE
        val vel = player.velocity
        
        currentState = when {
            player.abilities.flying -> MovementState.FLYING
            player.isOnGround && vel.horizontalLength() < 0.01 -> MovementState.IDLE
            player.isOnGround && player.isSprinting -> MovementState.RUNNING
            player.isOnGround -> MovementState.WALKING
            vel.y > 0.1 -> MovementState.ASCENDING
            vel.y < -0.1 && !player.isOnGround -> MovementState.FALLING
            else -> currentState // Keep previous state
        }
        
        return currentState
    }
    
    /**
     * Проверяет падение и автоматически восстанавливается
     * Вызывать каждый тик!
     */
    fun checkAndRecoverFromFall(client: MinecraftClient): Boolean {
        val player = client.player ?: return false
        
        detectMovementState(client)
        
        // Save safe position when flying or on ground safely
        if (currentState == MovementState.FLYING || 
            (player.isOnGround && player.y > 60)) {
            lastSafePosition = player.pos.add(0.0, 0.0, 0.0) // Clone
        }
        
        // Detect start of fall
        if (currentState == MovementState.FALLING && !isFalling) {
            isFalling = true
            fallStartTime = System.currentTimeMillis()
            fallStartPosition = lastSafePosition ?: player.pos.add(0.0, 5.0, 0.0)
            AquamixDrawBot.LOGGER.warn("Fall detected! Starting recovery...")
        }
        
        // If falling for more than 500ms, start recovery
        if (isFalling && System.currentTimeMillis() - fallStartTime > 500) {
            // RECOVERY: Enable fly and go back up
            ensureFlyActive(client)
            
            // Double-tap space simulation (for servers)
            InputOverrideHandler.setInputForced(BotInput.JUMP, true)
            
            // If we now have fly active, go to safe position
            if (player.abilities.flying || currentState == MovementState.FLYING) {
                val target = fallStartPosition ?: lastSafePosition
                if (target != null) {
                    moveTowards(client, target.x, target.y + 5.0, target.z, true)
                }
                
                // Check if recovered
                if (player.y >= (fallStartPosition?.y ?: 100.0)) {
                    isFalling = false
                    fallStartPosition = null
                    AquamixDrawBot.LOGGER.info("Fall recovery complete!")
                    return true // Recovered
                }
            }
            return true // Still recovering
        }
        
        // Reset if on ground
        if (player.isOnGround && isFalling) {
            isFalling = false
            // Landed on ground - need to take off again
            ensureFlyActive(client)
            InputOverrideHandler.setInputForced(BotInput.JUMP, true)
        }
        
        return false
    }
    
    fun ensureFlyActive(client: MinecraftClient) {
        val player = client.player ?: return
        
        if (player.abilities.allowFlying && !player.abilities.flying) {
            player.abilities.flying = true
            player.sendAbilitiesUpdate()
            isFlying = true
        }
        
        if (!player.abilities.allowFlying && !isFlying && canSendFlyCommand()) {
            sendFlyCommand(client)
        }
        
        if (player.isOnGround && !wasOnGround && isFlying) {
            if (!player.abilities.allowFlying) isFlying = false
        }
        wasOnGround = player.isOnGround
    }
    
    fun sendFlyCommand(client: MinecraftClient) {
        if (!canSendFlyCommand()) return
        client.player?.networkHandler?.sendChatCommand("fly")
        lastFlyCommand = System.currentTimeMillis()
        isFlying = true
    }
    
    private fun canSendFlyCommand(): Boolean {
        return System.currentTimeMillis() - lastFlyCommand > flyCommandCooldown
    }

    // Obstacle avoidance variables
    private var lastPos: net.minecraft.util.math.Vec3d? = null
    private var lastPosTime = 0L
    private var stuckTimer = 0L
    private var isStuck = false
    private var avoidanceDirection = 0 // 0 = none, 1 = right, -1 = left
    
    // Pathfinding state
    private var currentPath: List<BlockPos>? = null
    private var pathIndex = 0
    private var pathUpdateTimer = 0L
    
    private fun checkStuck(player: net.minecraft.entity.player.PlayerEntity) {
        val currentPos = player.pos
        val now = System.currentTimeMillis()
        
        if (lastPos == null) {
            lastPos = currentPos
            lastPosTime = now
            return
        }
        
        // Check distance moved every 500ms
        if (now - lastPosTime > 500) {
            val dist = currentPos.distanceTo(lastPos)
            if (dist < 0.5 && InputOverrideHandler.isInputForced(BotInput.MOVE_FORWARD)) {
                if (!isStuck) {
                    stuckTimer = now
                    isStuck = true
                    avoidanceDirection = if (Math.random() > 0.5) 1 else -1
                    AquamixDrawBot.LOGGER.warn("[FlightController] Stuck detected! Initiating avoidance.")
                }
            } else {
                isStuck = false
            }
            lastPos = currentPos
            lastPosTime = now
        }
    }

    /**
     * Двигаться к указанной точке с обходом препятствий
     */
    fun moveTowards(client: MinecraftClient, targetX: Double, targetY: Double, targetZ: Double, updateRotation: Boolean = true): Boolean {
        val player = client.player ?: return false
        
        checkStuck(player)
        
        // Handle Obstacle Avoidance
        if (isStuck || player.horizontalCollision) {
            // Fly UP and Strafe
            InputOverrideHandler.setInputForced(BotInput.JUMP, true)
            InputOverrideHandler.setInputForced(BotInput.MOVE_FORWARD, true)
            InputOverrideHandler.setInputForced(BotInput.SPRINT, true)
            
            if (avoidanceDirection == 1) {
                InputOverrideHandler.setInputForced(BotInput.MOVE_RIGHT, true)
                InputOverrideHandler.setInputForced(BotInput.MOVE_LEFT, false)
            } else {
                InputOverrideHandler.setInputForced(BotInput.MOVE_RIGHT, false)
                InputOverrideHandler.setInputForced(BotInput.MOVE_LEFT, true)
            }
            
            // Randomly change strafe direction if still stuck for long
            if (System.currentTimeMillis() - stuckTimer > 2000) {
                stuckTimer = System.currentTimeMillis() // Reset timer to switch direction
                avoidanceDirection = -avoidanceDirection
            }
            
            return false
        }
        
        // Normal movement
        val dx = targetX - player.x
        val dy = targetY - player.y
        val dz = targetZ - player.z
        
        val horizontalDist = sqrt(dx * dx + dz * dz)
        val totalDist = sqrt(dx * dx + dy * dy + dz * dz)
        
        val threshold = ModConfig.data.navigation.arrivalThreshold
        
        if (totalDist < threshold) {
            stopMovement(client)
            return true
        }
        
        // Поворот к цели
        if (updateRotation && horizontalDist > 0.1) {
            val targetYaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
            val targetPitch = Math.toDegrees(atan2(-dy, horizontalDist)).toFloat()
                .coerceIn(-60f, 60f)
            
            player.yaw = lerpAngle(player.yaw, targetYaw, 0.15f)
            player.pitch = lerpAngle(player.pitch, targetPitch, 0.1f)
        }
        
        // === Vertical Descent Logic (Fix for spinning high up) ===
        if (dy < -3.0) { // We are significantly above target
             if (horizontalDist <= 3.0) {
                 // Aligned horizontally (within 3 blocks): DROP
                 // Stop horizontal movement to prevent spiraling
                 InputOverrideHandler.setInputForced(BotInput.MOVE_FORWARD, horizontalDist > 0.5)
                 InputOverrideHandler.setInputForced(BotInput.SPRINT, false)
                 player.isSprinting = false
                 
                 // Drop down
                 InputOverrideHandler.setInputForced(BotInput.SNEAK, true)
                 InputOverrideHandler.setInputForced(BotInput.JUMP, false)
                 return false
             }
             // If dist > 3.0, allow diagonal descent (Standard PID below will handle SNEAK + FORWARD)
        }

        // Вертикальный спуск (fallback)
        if (dy < -5.0 && horizontalDist < 2.0) {
            InputOverrideHandler.setInputForced(BotInput.MOVE_FORWARD, false)
            InputOverrideHandler.setInputForced(BotInput.SNEAK, true)
            InputOverrideHandler.setInputForced(BotInput.JUMP, false)
            InputOverrideHandler.setInputForced(BotInput.SPRINT, false)
            return false
        }
        
        // Управление через InputOverrideHandler
        InputOverrideHandler.setInputForced(BotInput.MOVE_FORWARD, horizontalDist > threshold)
        
        // --- Adaptive Movement (Fly vs Walk/Run) ---
        if (player.abilities.flying) {
            // Flying: Force Sprint for speed
            InputOverrideHandler.setInputForced(BotInput.SPRINT, horizontalDist > 2.0)
            player.isSprinting = horizontalDist > 2.0
            
            // Vertical control (Flying)
            InputOverrideHandler.setInputForced(BotInput.JUMP, dy > 0.5)
            InputOverrideHandler.setInputForced(BotInput.SNEAK, dy < -0.5)
        } else {
            // Walking/Running (Survival/Ground)
            val isMoving = horizontalDist > threshold
            InputOverrideHandler.setInputForced(BotInput.SPRINT, isMoving && horizontalDist > 3.0)
            player.isSprinting = isMoving && horizontalDist > 3.0
            
            // Auto-Jump (Bunny Hop) if moving fast and safely
            // We jump if moving forward and not needing to go down
            if (isMoving && horizontalDist > 4.0 && player.isOnGround && dy >= -1.0) {
                 InputOverrideHandler.setInputForced(BotInput.JUMP, true)
            } else {
                 InputOverrideHandler.setInputForced(BotInput.JUMP, dy > 1.1) // Jump up blocks
            }
            
            // Descending logic for walking? (No sneak unless safe?)
            InputOverrideHandler.setInputForced(BotInput.SNEAK, false)
        }
        
        InputOverrideHandler.setInputForced(BotInput.MOVE_LEFT, false)
        InputOverrideHandler.setInputForced(BotInput.MOVE_RIGHT, false)
        
        return false
    }
    
    /**
     * Безопасный подъём с проактивной проверкой препятствий
     * - Raycast вперёд и вверх для обнаружения блоков
     * - Отступает назад если застрял
     * - Обходит препятствия по бокам
     */
    fun ascendSafely(client: MinecraftClient, targetHeight: Double) {
        val player = client.player ?: return
        val world = client.world ?: return
        
        // Check if stuck - if so, retreat backward
        checkStuck(player)
        if (isStuck) {
            // RETREAT: Move backward and up
            InputOverrideHandler.setInputForced(BotInput.MOVE_FORWARD, false)
            InputOverrideHandler.setInputForced(BotInput.MOVE_BACK, true)
            InputOverrideHandler.setInputForced(BotInput.JUMP, true)
            InputOverrideHandler.setInputForced(BotInput.SPRINT, false)
            
            // After 1s of retreat, try strafing
            if (System.currentTimeMillis() - stuckTimer > 1000) {
                InputOverrideHandler.setInputForced(BotInput.MOVE_BACK, false)
                if (avoidanceDirection == 1) {
                    InputOverrideHandler.setInputForced(BotInput.MOVE_RIGHT, true)
                    InputOverrideHandler.setInputForced(BotInput.MOVE_LEFT, false)
                } else {
                    InputOverrideHandler.setInputForced(BotInput.MOVE_RIGHT, false)
                    InputOverrideHandler.setInputForced(BotInput.MOVE_LEFT, true)
                }
            }
            
            // Change direction if stuck too long
            if (System.currentTimeMillis() - stuckTimer > 3000) {
                avoidanceDirection = -avoidanceDirection
                stuckTimer = System.currentTimeMillis()
            }
            return
        }
        
        // Proactive obstacle detection - raycast forward and up
        val lookVec = player.rotationVector
        val hasObstacleAhead = (1..3).any { dist ->
            val checkPos = player.blockPos.add(
                (lookVec.x * dist).toInt(),
                1, // Check 1 block above
                (lookVec.z * dist).toInt()
            )
            val state = world.getBlockState(checkPos)
            !state.isAir && state.isSolidBlock(world, checkPos)
        }
        
        // Check collision/stuck
        if (isStuck || hasObstacleAhead) {
            // Check if we need a new path
            val now = System.currentTimeMillis()
            if (currentPath == null || now - pathUpdateTimer > 2000) {
                 AquamixDrawBot.LOGGER.info("Ascent obstructed! Calculating path...")
                 val pathFinder = PathFinder(world)
                 
                 // Target center of chunk
                 val chunkX = (player.blockPos.x shr 4) shl 4 + 8
                 val chunkZ = (player.blockPos.z shr 4) shl 4 + 8
                 
                 currentPath = pathFinder.findPathToHeight(
                     player.blockPos, 
                     targetHeight.toInt(),
                     chunkX,
                     chunkZ
                 )
                 pathIndex = 0
                 pathUpdateTimer = now
            }
        }
        
        // If we have a path, follow it
        if (currentPath != null && pathIndex < currentPath!!.size) {
            val targetNode = currentPath!![pathIndex]
            val dist = player.pos.distanceTo(net.minecraft.util.math.Vec3d.ofBottomCenter(targetNode))
            
            if (dist < 1.0) {
                pathIndex++
                if (pathIndex >= currentPath!!.size) {
                    currentPath = null // Done
                    return
                }
            }
            
            // Move to next node
            val nextPos = currentPath!![pathIndex]
            moveTowards(client, nextPos.x + 0.5, nextPos.y + 0.1, nextPos.z + 0.5, true)
            
            // Force jump if going up
            if (nextPos.y > player.y) {
                 InputOverrideHandler.setInputForced(BotInput.JUMP, true)
            }
            return
        }

        // Fallback to simple proactive avoidance logic if no path found
        if (hasObstacleAhead) {
            // Obstacle detected ahead - fly up and strafe
            InputOverrideHandler.setInputForced(BotInput.JUMP, true)
            InputOverrideHandler.setInputForced(BotInput.MOVE_FORWARD, true)
            if (avoidanceDirection == 1) {
                InputOverrideHandler.setInputForced(BotInput.MOVE_RIGHT, true)
                InputOverrideHandler.setInputForced(BotInput.MOVE_LEFT, false)
            } else {
                InputOverrideHandler.setInputForced(BotInput.MOVE_RIGHT, false)
                InputOverrideHandler.setInputForced(BotInput.MOVE_LEFT, true)
            }
        } else {
            // Clear path so far - just ascend
            InputOverrideHandler.setInputForced(BotInput.JUMP, true)
            InputOverrideHandler.setInputForced(BotInput.MOVE_FORWARD, false)
            InputOverrideHandler.setInputForced(BotInput.MOVE_BACK, false)
            InputOverrideHandler.setInputForced(BotInput.MOVE_LEFT, false)
            InputOverrideHandler.setInputForced(BotInput.MOVE_RIGHT, false)
            InputOverrideHandler.setInputForced(BotInput.SPRINT, false)
            
            // Align to center slowly
            val chunkX = ((player.blockPos.x shr 4) shl 4) + 8.0
            val chunkZ = ((player.blockPos.z shr 4) shl 4) + 8.0
             
            val distToCenter = sqrt(
                (player.x - chunkX) * (player.x - chunkX) +
                (player.z - chunkZ) * (player.z - chunkZ)
            )
            
            // Gentle nudge to center if far away
            if (distToCenter > 4.0) {
                 moveTowards(client, chunkX, player.y + 1, chunkZ, true)
            }
        }
    }
    
    fun flyToChunk(client: MinecraftClient, chunk: ChunkPos): Boolean {
        val flightHeight = ModConfig.data.navigation.flightHeight.toDouble()
        val player = client.player ?: return false
        
        val targetX = chunk.blockX.toDouble()
        val targetZ = chunk.blockZ.toDouble()
        val dx = targetX - player.x
        val dz = targetZ - player.z
        val horizontalDist = sqrt(dx * dx + dz * dz)
        
        player.isSprinting = true
        
        // Early transition to Landing if close (1 chunk radius)
        if (horizontalDist < 16.0) {
            stopMovement(client)
            return true
        }
        
        if (horizontalDist < 64) {
            return moveTowards(client, targetX, player.y, targetZ)
        }
        
        if (player.y < flightHeight - 2) {
            InputOverrideHandler.setInputForced(BotInput.JUMP, true)
            InputOverrideHandler.setInputForced(BotInput.SNEAK, false)
            
            if (horizontalDist > 5) {
                moveTowards(client, targetX, player.y, targetZ)
                InputOverrideHandler.setInputForced(BotInput.JUMP, true)
            }
            
            player.pitch = -30f
            return false
        }
        
        InputOverrideHandler.setInputForced(BotInput.JUMP, false)
        
        val threshold = ModConfig.data.navigation.arrivalThreshold
        if (horizontalDist < threshold) {
            stopMovement(client)
            return true
        }
        
        return moveTowards(client, targetX, flightHeight, targetZ)
    }
    
    fun landInChunk(client: MinecraftClient, chunk: ChunkPos): Boolean {
        val player = client.player ?: return false
        val world = client.world ?: return false
        
        val targetX = chunk.blockX.toDouble() + 0.5
        val targetZ = chunk.blockZ.toDouble() + 0.5
        
        var groundY = 64
        for (y in player.blockY downTo world.bottomY) {
            val pos = net.minecraft.util.math.BlockPos(chunk.blockX, y, chunk.blockZ)
            val block = world.getBlockState(pos)
            if (!block.isAir && !block.isLiquid) {
                groundY = y + 1
                break
            }
        }
        
        val targetY = groundY.toDouble() + 0.1
        
        if (player.isOnGround || player.y - groundY < 1.0) {
            stopMovement(client)
            isFlying = false
            return true
        }
        
        val dy = targetY - player.y
        InputOverrideHandler.setInputForced(BotInput.SNEAK, dy < -0.5)
        InputOverrideHandler.setInputForced(BotInput.JUMP, false)
        
        return moveTowards(client, targetX, targetY, targetZ)
    }
    
    fun stopMovement(client: MinecraftClient) {
        InputOverrideHandler.clearAll()
        client.player?.isSprinting = false
    }
    
    private fun lerpAngle(from: Float, to: Float, t: Float): Float {
        var diff = to - from
        while (diff < -180) diff += 360
        while (diff > 180) diff -= 360
        return from + diff * t
    }

    fun hoverAbove(client: MinecraftClient, target: net.minecraft.util.math.BlockPos, updateRotation: Boolean = false) {
        val targetX = target.x + 0.5
        val targetY = target.y + 2.5 // Hover 2.5 blocks above
        val targetZ = target.z + 0.5
        
        moveTowards(client, targetX, targetY, targetZ, updateRotation)
    }

    fun reset() {
        isFlying = false
        wasOnGround = true
        lastPos = null
        isStuck = false
        InputOverrideHandler.clearAll()
    }
}
