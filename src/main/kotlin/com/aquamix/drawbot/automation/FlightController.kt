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
    
    // Takeoff state machine (Gemini recommendation)
    private var needsTakeoff = false
    private var takeoffTickCounter = 0
    private var takeoffStartTime = 0L
    
    // State for overshoot detection
    private var minDistanceToTarget = Double.MAX_VALUE
    private var lastTargetChunk: com.aquamix.drawbot.automation.ChunkPos? = null
    
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
        
        // Already flying? Done.
        if (player.abilities.flying) {
            isFlying = true
            needsTakeoff = false
            return
        }
        
        // Server mode: First, send /fly if we don't have allowFlying yet
        if (!player.abilities.allowFlying && canSendFlyCommand()) {
            sendFlyCommand(client)
            // Start takeoff sequence after command is sent
            needsTakeoff = true
            takeoffTickCounter = 0
            takeoffStartTime = System.currentTimeMillis()
            return
        }
        
        // If allowFlying but not flying -> Double-jump to activate flight!
        // This is the standard Minecraft mechanism: 2x Space = fly mode ON
        if (player.abilities.allowFlying && !player.abilities.flying) {
            if (!needsTakeoff) {
                needsTakeoff = true
                takeoffTickCounter = 0
                takeoffStartTime = System.currentTimeMillis()
                AquamixDrawBot.LOGGER.info("Starting double-jump takeoff sequence...")
            }
        }
        
        // Double-jump state machine: Jump -> Release -> Jump -> Release
        // Each tick is ~50ms, so we spread jumps across ticks
        if (needsTakeoff) {
            when (takeoffTickCounter) {
                0, 1 -> {
                    // First jump press (2 ticks)
                    InputOverrideHandler.setInputForced(BotInput.JUMP, true)
                }
                2, 3 -> {
                    // Release (2 ticks)
                    InputOverrideHandler.setInputForced(BotInput.JUMP, false)
                }
                4, 5 -> {
                    // Second jump press (2 ticks) - this should activate flying
                    InputOverrideHandler.setInputForced(BotInput.JUMP, true)
                }
                6 -> {
                    // Done - check if flight activated
                    InputOverrideHandler.setInputForced(BotInput.JUMP, false)
                    needsTakeoff = false
                    if (player.abilities.flying) {
                        isFlying = true
                        AquamixDrawBot.LOGGER.info("Double-jump takeoff SUCCESS!")
                    } else {
                        AquamixDrawBot.LOGGER.warn("Double-jump takeoff failed, will retry...")
                    }
                }
            }
            takeoffTickCounter++
            
            // Timeout after 1.5 seconds - retry
            if (System.currentTimeMillis() - takeoffStartTime > 1500) {
                needsTakeoff = false
                takeoffTickCounter = 0
                AquamixDrawBot.LOGGER.warn("Takeoff timeout, resetting...")
            }
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
    
    /**
     * Volume raycasting - проверяет проходимость пути с учётом хитбокса игрока (0.6x1.8x0.6)
     * Рекомендация Gemini: использовать Box.stretch вместо одиночного луча
     */
    fun isPathClear(world: net.minecraft.world.World, start: net.minecraft.util.math.Vec3d, end: net.minecraft.util.math.Vec3d): Boolean {
        // Player hitbox: 0.6 x 1.8 x 0.6
        val box = net.minecraft.util.math.Box(
            start.x - 0.3, start.y, start.z - 0.3,
            start.x + 0.3, start.y + 1.8, start.z + 0.3
        )
        val movementVec = end.subtract(start)
        val sweptBox = box.stretch(movementVec)
        
        // Check for any block collisions
        return !world.getBlockCollisions(null, sweptBox).iterator().hasNext()
    }
    
    /**
     * Проверяет есть ли твёрдый блок над игроком (потолок)
     */
    fun hasCeilingAbove(world: net.minecraft.world.World, player: net.minecraft.entity.player.PlayerEntity, height: Int = 3): Boolean {
        val pos = player.blockPos
        for (y in 1..height) {
            val above = pos.up(y)
            val state = world.getBlockState(above)
            if (state.isSolidBlock(world, above)) {
                return true
            }
        }
        return false
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
        // ONLY when flying - sneaking is fly-down key
        if (dy < -3.0 && player.abilities.flying) { // We are significantly above target
             if (horizontalDist <= 3.0) {
                 // Aligned horizontally (within 3 blocks): DROP
                 // Stop horizontal movement to prevent spiraling
                 InputOverrideHandler.setInputForced(BotInput.MOVE_FORWARD, horizontalDist > 0.5)
                 InputOverrideHandler.setInputForced(BotInput.SPRINT, false)
                 player.isSprinting = false
                 
                 // Drop down (flying sneak = descend)
                 InputOverrideHandler.setInputForced(BotInput.SNEAK, true)
                 InputOverrideHandler.setInputForced(BotInput.JUMP, false)
                 return false
             }
             // If dist > 3.0, allow diagonal descent (Standard PID below will handle SNEAK + FORWARD)
        }

        // Вертикальный спуск (fallback) - ONLY when flying
        if (dy < -5.0 && horizontalDist < 2.0 && player.abilities.flying) {
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
        
        // Check if stuck - if so, retry with VIOLENCE (Mining)
        checkStuck(player)
        if (isStuck) {
            // New Logic: If obstructed, MINE through it
            val lookVec = player.rotationVector
            val blockAheadPos = player.blockPos.add(
                (lookVec.x * 1.5).toInt(), 
                0, 
                (lookVec.z * 1.5).toInt()
            )
            val blockAheadState = world.getBlockState(blockAheadPos)
            val blockHeadState = world.getBlockState(player.blockPos.up()) // Head level
            
            val isObstructed = !blockAheadState.isAir || !blockHeadState.isAir
            
            if (isObstructed) {
                 AquamixDrawBot.LOGGER.warn("Stuck & Obstructed! Mining...")
                 
                 // Equip Pickaxe
                 AquamixDrawBot.botController.inventoryManager.equipBestPickaxe(client)
                 
                 // Attack!
                 InputOverrideHandler.setInputForced(BotInput.ATTACK, true)
                 InputOverrideHandler.setInputForced(BotInput.MOVE_FORWARD, true)
                 InputOverrideHandler.setInputForced(BotInput.SPRINT, false)
                 InputOverrideHandler.setInputForced(BotInput.JUMP, false) // Don't jump while mining
                 
                 // Reset stuck timer periodically to re-evaluate
                 if (System.currentTimeMillis() - stuckTimer > 2000) {
                     stuckTimer = System.currentTimeMillis()
                 }
                 return
            } else {
                 // Not obstructed but still stuck? Maybe clip/lag. Try Jump.
                 InputOverrideHandler.setInputForced(BotInput.JUMP, true)
                 InputOverrideHandler.setInputForced(BotInput.MOVE_FORWARD, true)
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
        val player = client.player ?: return false
        
        // Reset state if target changed
        if (lastTargetChunk != chunk) {
            minDistanceToTarget = Double.MAX_VALUE
            lastTargetChunk = chunk
        }
        
        // Target CENTER of chunk (x*16 + 8)
        val targetX = (chunk.x shl 4) + 8.0
        val targetZ = (chunk.z shl 4) + 8.0
        
        val dx = targetX - player.x
        val dz = targetZ - player.z
        val horizontalDist = sqrt(dx * dx + dz * dz)
        
        // --- Smart Arrival Logic ---
        
        // 1. Update minimum distance seen
        if (horizontalDist < minDistanceToTarget) {
            minDistanceToTarget = horizontalDist
        }
        
        val threshold = ModConfig.data.navigation.arrivalThreshold.coerceAtLeast(1.0)
        
        // 2. Check direct arrival
        if (horizontalDist < threshold) {
            stopMovement(client)
            return true
        }
        
        // 3. Overshoot detection:
        // If we were very close (< threshold + 3.0 blocks) 
        // AND now we are moving AWAY (current > min + 0.5)
        // THEN we probably just flew past the center. Stop and accept it.
        if (minDistanceToTarget < threshold + 3.0 && horizontalDist > minDistanceToTarget + 0.5) {
            if (System.currentTimeMillis() % 1000 < 50) {
                AquamixDrawBot.LOGGER.info("Overshoot detected! Forcing arrival at dist $horizontalDist (min was $minDistanceToTarget)")
            }
            stopMovement(client)
            return true
        }
        
        // Use config height, but don't force ascent if already flying at reasonable height
        // Use config height ONLY if we are too low
        // Otherwise maintain current altitude to prevent annoying ascents (Baritone-style)
        val configHeight = ModConfig.data.navigation.flightHeight.toDouble()
        val currentY = player.y
        
        // Target Y: If we are high enough (>70), stay there. If low, go to config height.
        // This prevents flying up after every chunk.
        val targetFlyHeight = if (currentY < 70.0) configHeight else currentY
        val minFlightHeight = targetFlyHeight - 5.0
        
        // 4. Ascent and Movement
        // ONLY ascend if we are dangerously low or path is obstructed
        // Don't just fly up because "config says so" if we can fly diagonal
        val dangerouslyLow = player.y < 70.0 // Assume void/danger is low
        
        // Check if direct path to target height is clear
        val targetPos = net.minecraft.util.math.Vec3d(targetX, targetFlyHeight, targetZ)
        val pathClear = isPathClear(client.world!!, player.pos, targetPos)
        
        if (dangerouslyLow || !pathClear) {
             val needsAscent = player.y < minFlightHeight && !player.abilities.flying
             if (needsAscent) {
                 // REMOVED: ascendSafely(client, configHeight)
                 return false
             }
        }
        
        // If path IS clear, we just fly towards target (FlyController.moveTowards handles the rest)
        
        ensureFlyActive(client)
        
        // Slow down near target (within 15 blocks)
        if (horizontalDist < 15.0) {
            InputOverrideHandler.setInputForced(BotInput.SPRINT, false)
            player.isSprinting = false
        } else {
            InputOverrideHandler.setInputForced(BotInput.SPRINT, true)
            player.isSprinting = true
        }
        
        InputOverrideHandler.setInputForced(BotInput.JUMP, false)
        InputOverrideHandler.setInputForced(BotInput.SNEAK, false)
        
        return moveTowards(client, targetX, targetFlyHeight, targetZ)
    }
    
    fun flyToBlock(client: MinecraftClient, targetBlock: BlockPos): Boolean {
        val player = client.player ?: return false

        // Target: 2 blocks above the block (Hover position)
        val targetX = targetBlock.x + 0.5
        val targetY = targetBlock.y + 2.0
        val targetZ = targetBlock.z + 0.5
        
        val dx = targetX - player.x
        val dy = targetY - player.y
        val dz = targetZ - player.z
        val horizontalDist = sqrt(dx * dx + dz * dz)
        val totalDist = sqrt(dx * dx + dy * dy + dz * dz)
        
        // 1. Arrival check
        if (totalDist < 1.0) {
            stopMovement(client)
            return true
        }

        ensureFlyActive(client)

        // 2. Direct flight (ignore safe height unless obstructed)
        // Check obstruction only if we are moving significantly
        if (totalDist > 5.0) {
             val pathClear = isPathClear(client.world!!, player.pos, net.minecraft.util.math.Vec3d(targetX, targetY, targetZ))
             if (!pathClear) {
                 // Path blocked! Use safe ascent logic
                 // Use max(currentY, targetY + 5) as safe height
                 val safeHeight = kotlin.math.max(player.y, targetY) + 10.0
                 // REMOVED: ascendSafely(client, safeHeight)
                 return false
             }
        }
        
        // Speed control
        if (totalDist < 5.0) {
            InputOverrideHandler.setInputForced(BotInput.SPRINT, false)
            player.isSprinting = false
        } else {
            InputOverrideHandler.setInputForced(BotInput.SPRINT, true)
            player.isSprinting = true
        }
        
        // Direct move
        moveTowards(client, targetX, targetY, targetZ, true)
        
        // Vertical corrections
        if (dy > 0.5) {
             InputOverrideHandler.setInputForced(BotInput.JUMP, true)
             InputOverrideHandler.setInputForced(BotInput.SNEAK, false)
        } else if (dy < -0.5) {
             InputOverrideHandler.setInputForced(BotInput.SNEAK, true)
             InputOverrideHandler.setInputForced(BotInput.JUMP, false)
        } else {
             InputOverrideHandler.setInputForced(BotInput.JUMP, false)
             InputOverrideHandler.setInputForced(BotInput.SNEAK, false)
        }
        
        return false
    }

    fun landInChunk(client: MinecraftClient, chunk: ChunkPos): Boolean {
        val player = client.player ?: return false
        val world = client.world ?: return false
        
        // Find center of chunk
        val centerX = (chunk.x shl 4) + 8
        val centerZ = (chunk.z shl 4) + 8
        val targetX = centerX + 0.5
        val targetZ = centerZ + 0.5
        
        // Find ground level (first solid block from top)
        var groundY = 64
        var isWaterAboveGround = false
        for (y in player.blockY + 5 downTo world.bottomY) {
            val pos = net.minecraft.util.math.BlockPos(centerX, y, centerZ)
            val block = world.getBlockState(pos)
            
            // Check if this is water
            if (!block.fluidState.isEmpty) {
                isWaterAboveGround = true
            }
            
            // Found solid ground
            if (!block.isAir && block.fluidState.isEmpty && block.isSolidBlock(world, pos)) {
                groundY = y + 1
                break
            }
        }
        
        // Target position: hover 2-3 blocks above ground (works for land AND water)
        val hoverHeight = if (isWaterAboveGround) 3.0 else 1.5
        val targetY = groundY.toDouble() + hoverHeight
        
        val dy = player.y - targetY
        val horizontalDist = sqrt((player.x - targetX) * (player.x - targetX) + (player.z - targetZ) * (player.z - targetZ))
        
        // Arrived condition: close to target position (for both water and land)
        if (horizontalDist < 2.0 && kotlin.math.abs(dy) < 2.0) {
            // For water: we're in position, consider landed
            // For land: check if actually on ground
            if (isWaterAboveGround || player.isOnGround || dy < 1.0) {
                stopMovement(client)
                return true
            }
        }
        
        // Move to position
        if (dy > 1.0) {
            // Descend
            InputOverrideHandler.setInputForced(BotInput.SNEAK, true)
            InputOverrideHandler.setInputForced(BotInput.JUMP, false)
        } else if (dy < -1.0) {
            // Ascend (we're too low)
            InputOverrideHandler.setInputForced(BotInput.JUMP, true)
            InputOverrideHandler.setInputForced(BotInput.SNEAK, false)
        } else {
            // At right height
            InputOverrideHandler.setInputForced(BotInput.JUMP, false)
            InputOverrideHandler.setInputForced(BotInput.SNEAK, false)
        }
        
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
