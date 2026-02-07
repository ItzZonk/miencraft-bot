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

// Removed Baritone imports

/**
 * Контроллер полёта - использует InputOverrideHandler для управления
 * Движение применяется через AquamixInput mixin
 */
class FlightController {
    private var lastFlyCommand = 0L
    private var flyCommandCooldown = 2000L
    
    private var isFlying = false
    private var wasOnGround = true
    
    // Custom Pathfinding State
    private var pathFinder: PathFinder? = null
    private var currentPath: List<BlockPos>? = null
    private var pathIndex = 0
    
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
    
    // Stuck Detection
    private var lastPosTime = 0L
    private var lastPos = net.minecraft.util.math.Vec3d.ZERO
    private var stuckTicks = 0
    
    // Recovery
    private var isRecovering = false
    private var recoveryStartTime = 0L
    
    fun resetStuck() {
        stuckTicks = 0
        isRecovering = false
    }

    /**
     * Checks if we are stuck and triggers recovery
     */
    fun checkStuck(client: MinecraftClient): Boolean {
        val player = client.player ?: return false
        
        if (isRecovering) {
            handleRecovery(client)
            return true
        }
        
        val pos = player.pos
        val dist = pos.distanceTo(lastPos)
        lastPos = pos
        
        // If we are trying to move but not moving
        // (Threshold 0.05 is very small movement)
        if (dist < 0.05 && currentState == MovementState.FLYING) {
            stuckTicks++
        } else {
            stuckTicks = 0
        }
        
        if (stuckTicks > 20) { // 1 second stuck
            AquamixDrawBot.LOGGER.warn("Stuck detected! Initiating recovery...")
            isRecovering = true
            recoveryStartTime = System.currentTimeMillis()
            stuckTicks = 0
            return true
        }
        return false
    }
    
    private fun handleRecovery(client: MinecraftClient) {
        val player = client.player ?: return
        val elapsed = System.currentTimeMillis() - recoveryStartTime
        
        if (elapsed > 1000) {
            isRecovering = false
            AquamixDrawBot.LOGGER.info("Recovery finished.")
            return
        }
        
        // WIGGLE: REMOVED (User Feedback: "Stop spinning")
        // Just fly UP to clear the hole/tree
        
        // JUMP / UP
        InputOverrideHandler.setInputForced(BotInput.JUMP, true)
        
        // Backward / Forward pulses to wiggle out of corner physics
        if (elapsed < 500) {
            InputOverrideHandler.setInputForced(BotInput.MOVE_BACK, true)
            InputOverrideHandler.setInputForced(BotInput.MOVE_FORWARD, false)
        } else {
            InputOverrideHandler.setInputForced(BotInput.MOVE_BACK, false)
            InputOverrideHandler.setInputForced(BotInput.MOVE_FORWARD, true)
        }
    }

    fun ensureFlyActive(client: MinecraftClient) {
        val player = client.player ?: return
        
        // ALWAYS FLY: No landing ever.
        if (!player.abilities.flying && player.abilities.allowFlying) {
             player.abilities.flying = true
             player.sendAbilitiesUpdate()
        }
        
        // If we are falling and can't fly, try command
        if (!player.abilities.flying) {
             val now = System.currentTimeMillis()
             if (now - lastFlyCommand > 2000) {
                 client.networkHandler?.sendChatCommand("fly")
                 lastFlyCommand = now
             }
        }
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
     * FLY TO CHUNK - CUSTOM LOGIC
     * 1. Calculate path to center of chunk at target height.
     * 2. Store path.
     * 3. Follow path.
     */
    /**
     * FLY TO CHUNK - CUSTOM LOGIC
     * 1. Calculate path to center of chunk at target height.
     * 2. Store path.
     * 3. Follow path.
     */
    fun flyToChunk(client: MinecraftClient, chunk: ChunkPos): Boolean {
        val player = client.player ?: return false
        ensureFlyActive(client)
        
        // Target definition
        val centerX = (chunk.x shl 4) + 8
        val centerZ = (chunk.z shl 4) + 8
        
        // Use current height if reasonable, otherwise config height
        // If we are high up, stay high up (over mountains)
        // If we are too low (near bedrock?), go up
        val configHeight = ModConfig.data.navigation.flightHeight.toDouble()
        val targetY = if (player.y > configHeight) player.y else configHeight
        
        // If we are already close to target chunk 2D, check completion
        val distToCenter = sqrt((player.x - centerX) * (player.x - centerX) + (player.z - centerZ) * (player.z - centerZ))
        if (distToCenter < 1.0) {
             currentPath = null
             stopMovement(client)
             return true
        }
        
        // Path handling - Check line of sight
        val targetVec = net.minecraft.util.math.Vec3d(centerX.toDouble(), targetY, centerZ.toDouble())
        
        // Check if we need pathfinding (if blocked)
        if (currentPath == null || pathIndex >= (currentPath?.size ?: 0)) {
            if (!isLineOfSightClear(client, player.pos, targetVec)) {
                 if (pathFinder == null) pathFinder = PathFinder(client.world!!)
                 
                 val targetPos = BlockPos(centerX, targetY.toInt(), centerZ)
                 AquamixDrawBot.LOGGER.info("Calculating new flight path to $targetPos...")
                 val rawPath = pathFinder?.findPath(player.blockPos, targetPos)
                 
                 // SMOOTHING: Optimize the path
                 currentPath = if (rawPath != null) smoothPath(client, rawPath) else null
                 pathIndex = 0
            }
        }
        
        if (currentPath != null) {
            // Follow Path
            val path = currentPath!!
            
            if (pathIndex < path.size) {
                val nextNode = path[pathIndex]
                val nodeX = nextNode.x + 0.5
                val nodeY = nextNode.y + 0.5
                val nodeZ = nextNode.z + 0.5
                
                // Check radius to current node
                val distToNode = player.squaredDistanceTo(nodeX, nodeY, nodeZ)
                if (distToNode < 4.0) {
                    pathIndex++
                }
<<<<<<< HEAD
        }
        
        // CRITICAL: Predictive Re-pathing (Kimi Fix 1)
        val pathSafety = validatePathAhead(client, path, pathIndex)
        if (pathSafety < 0.5f) {
             AquamixDrawBot.LOGGER.warn("Path ahead unsafe (score: $pathSafety)! Re-calculating...")
             currentPath = null // Force re-path next tick
             stopMovement(client)
             return false
        } else if (pathSafety < 1.0f) {
            // Slow down if uncertain
             InputOverrideHandler.setInputForced(BotInput.SPRINT, false)
        } else {
             // Safe to sprint
             InputOverrideHandler.setInputForced(BotInput.SPRINT, true)
        }
        
        val distToNode = player.squaredDistanceTo(nodeX, nodeY, nodeZ)
        // Optimization: Larger acceptance radius for intermediate nodes (speed!)
        val acceptanceRadiusSq = if (pathIndex < path.size - 1) 4.0 else 1.0 // 2.0 blocks for intermediate
            
            if (distToNode < acceptanceRadiusSq) {
                pathIndex++
                if (pathIndex >= path.size) {
                    currentPath = null
                    return false // Last step
=======
                
                // Optimization: Skip nodes if visual path is clear
                if (pathIndex + 1 < path.size) {
                    val nextNext = path[pathIndex + 1]
                    val nnVec = net.minecraft.util.math.Vec3d.ofCenter(nextNext)
                    if (isLineOfSightClear(client, player.pos, nnVec)) {
                         pathIndex++
                    }
>>>>>>> d4380ea (Save local progress and add prompts)
                }
                
                moveTowards(client, nodeX, nodeY, nodeZ)
                return false
            }
        }
        
        // Fallback or Direct Flight (if Line of Sight is clear or path failed)
        moveTowards(client, centerX.toDouble(), targetY, centerZ.toDouble())
        return false
    }
    
    /**
     * Trajectory Pruning (Math Shoot)
     * Reduces node count by connecting visible non-adjacent nodes.
     */
    private fun smoothPath(client: MinecraftClient, path: List<BlockPos>): List<BlockPos> {
        if (path.size < 3) return path
        
        val smoothed = mutableListOf<BlockPos>()
        smoothed.add(path[0])
        
        var currentIdx = 0
        while (currentIdx < path.size - 1) {
            var nextIdx = currentIdx + 1
            
            // Look ahead as far as possible
            for (i in path.size - 1 downTo currentIdx + 2) {
                val start = path[currentIdx]
                val end = path[i]
                
                // Convert to Vec3d centers
                val startVec = net.minecraft.util.math.Vec3d.ofCenter(start)
                val endVec = net.minecraft.util.math.Vec3d.ofCenter(end)
                
                if (isLineOfSightClear(client, startVec, endVec)) {
                    nextIdx = i
                    break
                }
            }
            
            smoothed.add(path[nextIdx])
            currentIdx = nextIdx
        }
        
        return smoothed
    }
    
    /**
     * Predictive Look-Ahead: Validates path ahead by checking multiple upcoming nodes.
     * Returns: Safety score (0.0 = blocked, 1.0 = clear)
     */
    private fun validatePathAhead(client: MinecraftClient, path: List<BlockPos>, currentIndex: Int): Float {
        if (currentIndex >= path.size - 1) return 1.0f
        
        val player = client.player ?: return 0.0f
        val lookAheadCount = kotlin.math.min(3, path.size - currentIndex - 1) // Check next 3 nodes
        var clearNodes = 0
        
        // 1. Check immediate next node (Line of Sight)
        // We use eye pos for this first check
        val nextNode = path[currentIndex]
        val nextVec = net.minecraft.util.math.Vec3d.ofCenter(nextNode)
        if (!isLineOfSightClear(client, player.eyePos, nextVec)) return 0.0f
        
        // 2. Check subsequent nodes with Width Clearance
        for (i in 1..lookAheadCount) {
            val futureNode = path[currentIndex + i]
            val futureVec = net.minecraft.util.math.Vec3d.ofCenter(futureNode)
            
            // Wider raycast for future nodes (accounting for drift/width)
            // 0.6 is slightly larger than player width (0.6) for safety
            val clearance = checkClearance(client, futureVec, 0.4) 
            if (clearance < 0.5) break // Path blocked or unsafe
            
            clearNodes++
        }
        
        return clearNodes.toFloat() / lookAheadCount.coerceAtLeast(1)
    }
    
    /**
     * Enhanced clearance check that accounts for player width (Kimi Fix 2)
     */
    private fun checkClearance(client: MinecraftClient, center: net.minecraft.util.math.Vec3d, radius: Double): Float {
        val world = client.world ?: return 0.0f
        val offsets = listOf(
            net.minecraft.util.math.Vec3d(radius, 0.0, 0.0), 
            net.minecraft.util.math.Vec3d(-radius, 0.0, 0.0),
            net.minecraft.util.math.Vec3d(0.0, 0.0, radius), 
            net.minecraft.util.math.Vec3d(0.0, 0.0, -radius),
            net.minecraft.util.math.Vec3d(0.0, 1.0, 0.0), // Head/Body center
            net.minecraft.util.math.Vec3d(0.0, -1.0, 0.0) // Feet
        )
        
        var blocked = 0
        for (offset in offsets) {
            val checkPos = center.add(offset)
            val blockPos = net.minecraft.util.math.BlockPos(checkPos.x.toInt(), checkPos.y.toInt(), checkPos.z.toInt())
            val state = world.getBlockState(blockPos)
            
            if (state.isSolidBlock(world, blockPos) || state.block.name.string.lowercase().contains("leaves")) {
                blocked++
            }
        }
        
        return 1.0f - (blocked.toFloat() / offsets.size)
    }

    private fun isLineOfSightClear(client: MinecraftClient, start: net.minecraft.util.math.Vec3d, end: net.minecraft.util.math.Vec3d): Boolean {
        val world = client.world ?: return false
        val context = net.minecraft.world.RaycastContext(
            start, 
            end, 
            net.minecraft.world.RaycastContext.ShapeType.COLLIDER, 
            net.minecraft.world.RaycastContext.FluidHandling.NONE, 
            client.player
        )
        val result = world.raycast(context)
        // Also check if result block is leaves/log?
        if (result.type == net.minecraft.util.hit.HitResult.Type.BLOCK) {
            val state = world.getBlockState(result.blockPos)
            val name = state.block.name.string.lowercase()
            if (name.contains("leaves") || name.contains("log")) return false
        }
        return result.type == net.minecraft.util.hit.HitResult.Type.MISS
    }
    
    // Expose path for Renderer
    fun getCurrentPath(): List<BlockPos>? = currentPath
    
    /**
     * Executes SMART flight movement.
     * Replaces simple vector math with terrain-aware raycasting.
     */
    /**
     * Executes FLUID VECTOR FIELD flight movement.
     * Uses potential fields (Attraction - Repulsion) for smooth obstacle avoidance.
     */
    /**
     * Executes DIRECT Baritone-style flight movement.
     * Simply looks at the target and moves forward.
     * Relies on PathFinder for obstacle avoidance.
     */
    fun moveTowards(client: MinecraftClient, targetX: Double, targetY: Double, targetZ: Double, updateRotation: Boolean = true): Boolean {
        val player = client.player ?: return false
        
        // 1. Calculate Vector to Target
        val dx = targetX - player.x
        val dy = targetY - player.y
        val dz = targetZ - player.z
        val distToTargetSq = dx * dx + dy * dy + dz * dz
        val distToTarget = sqrt(distToTargetSq)
        
        // Arrival check
        if (distToTarget < 0.5) {
            stopMovement(client)
            return true
        }
        
        // 2. Rotation (Look where we are going)
        // Baritone style: Snap to target instantly
        if (updateRotation) {
            val yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
            val pitch = Math.toDegrees(atan2(-dy, sqrt(dx*dx + dz*dz))).toFloat().coerceIn(-90f, 90f)
            
            player.yaw = yaw
            player.pitch = pitch
        }
        
        // 3. Inputs
        // Always move forward towards the target
        InputOverrideHandler.setInputForced(BotInput.MOVE_FORWARD, true) // Always press forward
        InputOverrideHandler.setInputForced(BotInput.SPRINT, true)       // Always sprint
        
        // Vertical Inputs based on Pitch/Vector Y
        // If looking up/down significantly, use Jump/Sneak to assist vertical movement
        // But mainly we trust the pitch + forward movement in creative flight
        if (player.abilities.flying) {
             // If we need to go up steeply
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
        }
        
        // 4. Stuck Check
        if (checkStuck(client)) return false
        
        return false
    }


    fun flyToBlock(client: MinecraftClient, targetBlock: BlockPos): Boolean {
        val player = client.player ?: return false
        ensureFlyActive(client)
        
        val targetX = targetBlock.x + 0.5
        val targetY = targetBlock.y + 2.0 // Hover
        val targetZ = targetBlock.z + 0.5
        
        // Check if we need pathfinding (if far away or obstructed)
        if (currentPath == null || pathIndex >= (currentPath?.size ?: 0)) {
            // Direct Line Check
            if (isLineOfSightClear(client, player.pos, net.minecraft.util.math.Vec3d(targetX, targetY, targetZ))) {
                 // Direct flight is safe
                 currentPath = null
            } else {
                 if (pathFinder == null) pathFinder = PathFinder(client.world!!)
                 val targetPos = BlockPos(targetBlock.x, targetBlock.y + 2, targetBlock.z)
                 currentPath = pathFinder?.findPath(player.blockPos, targetPos)
                 // Smooth it
                 currentPath = if (currentPath != null) smoothPath(client, currentPath!!) else null
                 pathIndex = 0
            }
        }
        
        if (currentPath != null) {
            // Follow path logic
            val path = currentPath!!
             if (pathIndex < path.size) {
                val nextNode = path[pathIndex]
                val nodeX = nextNode.x + 0.5
                val nodeY = nextNode.y + 0.5
                val nodeZ = nextNode.z + 0.5
                
                // Advance node if close
                if (player.squaredDistanceTo(nodeX, nodeY, nodeZ) < 4.0) {
                    pathIndex++
                }
                
                // If we can see next node, skip current
                if (pathIndex + 1 < path.size) {
                    val nextNext = path[pathIndex + 1]
                    val nnVec = net.minecraft.util.math.Vec3d.ofCenter(nextNext)
                    if (isLineOfSightClear(client, player.pos, nnVec)) {
                        pathIndex++
                    }
                }
                
                moveTowards(client, nodeX, nodeY, nodeZ)
                return false
            }
        }
        
        // Direct approach (Final stretch or no path needed)
        val dist = sqrt((player.x - targetX)*(player.x - targetX) + (player.y - targetY)*(player.y - targetY) + (player.z - targetZ)*(player.z - targetZ))
        if (dist < 0.5) {
            stopMovement(client)
            return true
        }
        
        moveTowards(client, targetX, targetY, targetZ)
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
        
        // Scan down from player or sky
        val startY = player.blockY.coerceAtLeast(world.bottomY + 10)
        
        for (y in startY downTo world.bottomY) {
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
        
        // Check arrival
        val horizontalDist = sqrt((player.x - targetX) * (player.x - targetX) + (player.z - targetZ) * (player.z - targetZ))
        val dy = player.y - targetY
        
        if (horizontalDist < 1.0 && kotlin.math.abs(dy) < 1.0) {
            stopMovement(client)
            return true
        }
        
        // Use manual move
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
        val player = client.player ?: return
        
        // Target Y logic detailed above...
        val currentY = player.y
        val relY = currentY - target.y
        
        val targetY = when {
            relY < 1.5 -> target.y + 3.0 // Go up
            relY > 6.0 -> target.y + 4.0 // Come down
            else -> currentY
        }
        
        moveTowards(client, target.x + 0.5, targetY, target.z + 0.5, updateRotation)
    }

    fun ascendSafely(client: MinecraftClient, targetHeight: Double) {
        val player = client.player ?: return
        moveTowards(client, player.x, targetHeight, player.z)
    }

    // Removed calculateRepulsionVector - Causes spinning and issues.
    // We trust the PathFinder or direct visual flight.

    fun reset() {
        isFlying = false
        wasOnGround = true
        InputOverrideHandler.clearAll()
        currentPath = null
    }
}
