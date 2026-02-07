# System Role
You are a Principal Software Engineer and expert in Minecraft Bot development, Control Theory, and Pathfinding algorithms. You are acting as the "Lead Architect" who analyzes the current codebase and prepares a detailed technical briefing for a "Senior Developer" (Kimi K2) who will implement the fixes.

# Task Overview
We are refactoring the **Aquamix Draw Bot**. The current implementation has several critical issues related to movement, pathfinding, and interaction logic. Your goal is to analyze the provided code and the user's detailed bug report, then generate a **comprehensive technical context and architecture modification plan**.

This output will be used to instruct another AI to write the actual code. Therefore, your response must be deeply technical, highlighting specific lines of code, logic flaws, and architectural bottlenecks.

# User Reported Issues
1.  **Inefficient Chunk Traversal**: The bot often flies to the *edge* of a chunk to place a drill, even if it is already in the middle. It seems to pick suboptimal target blocks.
2.  **Spinning / Freezing**: The bot sometimes gets stuck, spinning in circles or freezing while debugging shows inputs (Sprint + Forward) are active. This suggests a conflict between the `FlightController`'s rotation logic and the `InputOverrideHandler` or physics.
3.  **"Brute Force" Pathfinding**: When flying between chunks, the bot flies in a straight line, hits an obstacle, jumps/adjusts, and continues. It lacks predictive pathfinding or smooth avoidance, looking like it's "brute forcing" its way through terrain.
4.  **Control Loop Issues**: Sometimes the bot hangs and circles despite logic suggesting it should be moving.
5.  **Target Selection**: It doesn't always find the nearest block in the next chunk efficiently.

# Codebase Context
Here are the three critical files governing this behavior: `FlightController.kt` (Movement), `ChunkBreaker.kt` (Targeting/Interaction), and `BotController.kt` (State Machine).

## 1. FlightController.kt
```kotlin
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
                
                // Optimization: Skip nodes if visual path is clear
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
    
    private fun isLineOfSightClear(client: MinecraftClient, start: net.minecraft.util.math.Vec3d, end: net.minecraft.util.math.Vec3d): Boolean {
        val world = client.world ?: return false
        // Raycast
        // We use shape type COLLIDER to ignore grass/flowers but hit solids
        val context = net.minecraft.world.RaycastContext(
            start, 
            end, 
            net.minecraft.world.RaycastContext.ShapeType.COLLIDER, 
            net.minecraft.world.RaycastContext.FluidHandling.NONE, 
            client.player
        )
        val result = world.raycast(context)
        return result.type == net.minecraft.util.hit.HitResult.Type.MISS
    }
    
    // Expose path for Renderer
    fun getCurrentPath(): List<BlockPos>? = currentPath
    
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
```

## 2. ChunkBreaker.kt
```kotlin
package com.aquamix.drawbot.automation

import com.aquamix.drawbot.AquamixDrawBot
import com.aquamix.drawbot.config.ModConfig
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.item.Items
import net.minecraft.screen.slot.SlotActionType
import net.minecraft.util.Hand
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import kotlin.math.sqrt

/**
 * Переписанный обработчик БУРа
 * "The Perfect Interaction" Edition + 3D Scanner
 */
class ChunkBreaker {
    private var lastActionTime = 0L
    
    // Breaking State
    private var isBreaking = false
    private var currentBreakingBlock: BlockPos? = null
    
    // Целевой блок для установки БУРа (кэшируем чтобы не искать каждый тик)
    private var cachedTargetBlock: BlockPos? = null
    private var targetRotationDone = false
    
    // Exhaustive search state
    private var scanAttempts = 0
    private val failedBlocks = mutableSetOf<BlockPos>()

    /**
     * Сброс состояния для нового чанка
     */
    fun reset() {
        cachedTargetBlock = null
        targetRotationDone = false
        scanAttempts = 0
        failedBlocks.clear()
        stopBreaking()
    }
    
    // === Digging Logic ===
    
    private fun stopBreaking() {
         if (isBreaking) {
             val client = MinecraftClient.getInstance()
             client.interactionManager?.cancelBlockBreaking()
             isBreaking = false
             currentBreakingBlock = null
         }
    }
    
    /**
     * Управляет копанием блока (Hold to break)
     */
    private fun updateBreaking(client: MinecraftClient, target: BlockPos): Boolean {
         val interactionManager = client.interactionManager ?: return false
         val player = client.player ?: return false
         
         if (currentBreakingBlock != target) {
             stopBreaking()
             
             // Start new break
             lookAt(player, target)
             interactionManager.attackBlock(target, Direction.UP)
             player.swingHand(Hand.MAIN_HAND)
             
             isBreaking = true
             currentBreakingBlock = target
             return false
         } else {
             // Continue breaking (Hold button simulation)
             lookAt(player, target) // Keep looking
             if (interactionManager.updateBlockBreakingProgress(target, Direction.UP)) {
                 player.swingHand(Hand.MAIN_HAND) // Swing animation
             }
             
             // Check if broken
             if (client.world?.getBlockState(target)?.isAir == true) {
                 stopBreaking()
                 return true // DONE
             }
             return false // Still breaking
         }
    }

    /**
     * Находит ближайший твёрдый блок в ЦЕЛЕВОМ чанке и ставит на него БУР
     */
    fun placeBur(client: MinecraftClient, targetChunk: ChunkPos): Boolean {
        val player = client.player ?: return false
        val interactionManager = client.interactionManager ?: return false
        val world = client.world ?: return false
        
        if (!canAct()) return false
        
        // 1. Найти цель
        val targetBlock = getTarget(client, targetChunk)
        if (targetBlock == null) {
            if (scanAttempts % 50 == 0) {
                 AquamixDrawBot.LOGGER.info("Scanning chunk $targetChunk exhaustively (attempt $scanAttempts)...")
            }
            return false 
        }
        
        // Fail-Fast: Try multiple times per tick until success
        var attempts = 0
        var currentTarget: BlockPos? = targetBlock
        
        while (attempts < 5 && currentTarget != null) { // Try up to 5 candidates instantly
             attempts++
             val target = currentTarget!!
             
             // 2. Obstacle Clearing
             val blockAbove = target.up()
             val stateAbove = world.getBlockState(blockAbove)
             
             // If already placed?
             if (stateAbove.block == net.minecraft.block.Blocks.END_PORTAL_FRAME) {
                 // Already here
                 // ... interaction logic ...
                 val hitResult = BlockHitResult(Vec3d.ofCenter(blockAbove), Direction.UP, blockAbove, false)
                 lookAt(player, blockAbove)
                 interactionManager.interactBlock(player, Hand.MAIN_HAND, hitResult)
                 player.swingHand(Hand.MAIN_HAND)
                 markAction()
                 return true 
             }
             
             // Break grass/kelp instantly
             val isReplaceable = stateAbove.isReplaceable || stateAbove.block.name.string.contains("grass", ignoreCase = true)
             if (!stateAbove.isAir && (!isReplaceable || stateAbove.block.name.string.contains("Kelp", ignoreCase = true))) {
                  AquamixDrawBot.botController.inventoryManager.equipBestPickaxe(client)
                  val broken = updateBreaking(client, blockAbove)
                  if (!broken) {
                      markAction()
                      return false // Must wait for break
                  }
             } else {
                 stopBreaking()
             }
             
             // Equip BUR if needed (only once per tick optimization)
             val inventory = player.inventory
             var burSlot = -1
             for (i in 0..8) {
                if (inventory.getStack(i).item == Items.END_PORTAL_FRAME) {
                    burSlot = i
                    break
                }
             }
             if (burSlot == -1) return false
             if (inventory.selectedSlot != burSlot) inventory.selectedSlot = burSlot
             
             // Safety
             val burPos = target.up()
             if (player.boundingBox.intersects(net.minecraft.util.math.Box(burPos))) {
                 invalidateCurrentTarget() // Current target bad
                 // Try to get next target immediately?
                 // We need to fetch next best from `findBestTarget` excluding failed.
                 // But `findBestTarget` is expensive.
                 // Better: Use `tryPlaceAgainstNeighbors` on this bad pos.
             } else {
                 // Try Placement
                 lookAt(player, target)
                 val hitResult = BlockHitResult(Vec3d.ofCenter(target).add(0.0, 1.0, 0.0), Direction.UP, target, false)
                 val result = interactionManager.interactBlock(player, Hand.MAIN_HAND, hitResult)
                 
                 if (result.isAccepted) {
                     player.swingHand(Hand.MAIN_HAND)
                     markAction()
                     AquamixDrawBot.LOGGER.info("BUR placed!")
                     reset()
                     return true
                 } else {
                     // Try neighbors
                     if (tryPlaceAgainstNeighbors(client, burPos)) {
                         player.swingHand(Hand.MAIN_HAND)
                         markAction()
                         AquamixDrawBot.LOGGER.info("BUR neighbor placed!")
                         reset()
                         return true
                     }
                 }
             }
             
             // If we failed interaction or safety:
             invalidateCurrentTarget() 
             // Logic to pick NEXT best target?
             // `cachedTargetBlock` is now null. `getTarget` will return next best.
             val nextTarget = getTarget(client, targetChunk)
             // Loop continues if nextTarget is not null
             if (nextTarget == null) return false // No more targets
             currentTarget = nextTarget
        }
        
        return false
    }
    
    private fun tryPlaceAgainstNeighbors(client: MinecraftClient, targetPos: BlockPos): Boolean {
        val world = client.world ?: return false
        val player = client.player ?: return false
        val interactionManager = client.interactionManager ?: return false
        
        // Check all 6 neighbors of the target AIR block
        // Sort by distance to player
        val dirs = Direction.values().sortedBy { 
             targetPos.offset(it).getSquaredDistance(player.pos)
        }
        
        for (dir in dirs) {
            val neighbor = targetPos.offset(dir)
            val state = world.getBlockState(neighbor)
            
            if (state.isSolidBlock(world, neighbor)) {
                // Found a solid neighbor!
                // Place against it.
                val face = dir.opposite
                val hitResult = BlockHitResult(
                    Vec3d.ofCenter(neighbor).add(Vec3d.of(face.vector).multiply(0.5)),
                    face,
                    neighbor,
                    false
                )
                
                // Rotation
                lookAt(player, neighbor)
                
                val res = interactionManager.interactBlock(player, Hand.MAIN_HAND, hitResult)
                if (res.isAccepted) return true
            }
        }
        return false
    }

    fun getTarget(client: MinecraftClient, targetChunk: ChunkPos): BlockPos? {
        if (cachedTargetBlock == null) {
            cachedTargetBlock = findBestTarget(client, targetChunk)
            scanAttempts++
        }
        return cachedTargetBlock
    }

    /**
     * Находит лучший твёрдый блок в ЦЕЛЕВОМ чанке
     * "The 3D Scanner" Logic
     * Scans outward from player to find the ABSOLUTE CLOSEST valid block surface.
     */
    private fun findBestTarget(client: MinecraftClient, chunk: ChunkPos): BlockPos? {
        val world = client.world ?: return null
        val player = client.player ?: return null
        val playerPos = player.blockPos
        
        // Fast approach: Iterate chunk bounds, but verify distance.
        // The target MUST be in the chunk.
        
        val chunkMinX = chunk.minBlockX
        val chunkMaxX = chunk.maxBlockX
        val chunkMinZ = chunk.minBlockZ
        val chunkMaxZ = chunk.maxBlockZ
        
        var bestBlock: BlockPos? = null
        var bestDistSq = Double.MAX_VALUE
        
        // Optimization: iterate only the chunk x/z, but restrict Y to player range
        // Expanded Scan Radius (User Request: 256 blocks)
        val scanRadius = 256 // HUGE radius
        
        // Optimization: Spiral out from player chunk instead of iterating all
        val pChunkX = player.chunkPos.x
        val pChunkZ = player.chunkPos.z
        
        // Scan chunks in radius
        // To be safe and fast, we prioritize the TARGET chunk first, then spiral out if needed
        // BUT `findBestTarget` is called for a SPECIFIC target chunk usually.
        // Wait, the user wants "Scan Radius" for *finding targets*?
        // Ah, `findBestTarget` takes a `chunk: ChunkPos`. This function scans *inside* that chunk.
        // If the user wants to scan 256 blocks to find *which chunk* to go to, that's `BotController` logic.
        // BUT if they mean "Scan the target chunk vertically/horizontally", we already scan full chunk.
        
        // Let's assume user means: When looking for a spot to place BUR, check a huge area?
        // No, the bot flies to specific chunks in the queue.
        // Re-reading: "Increase 3D awareness radius... to better plan paths".
        // This likely refers to `PathFinder` or `LocalVoxelMap`.
        
        // However, let's ensure we scan the full vertical range of the target chunk.
        val startY = world.bottomY
        val endY = world.topY
        
        // The original code scanned +/- 30 blocks. User wants MORE visibility.
        // So we scan full height.
        
        for (x in chunkMinX..chunkMaxX) {
            for (z in chunkMinZ..chunkMaxZ) {
                // Optimization: Skip empty columns using heightmap
                val topY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, x, z)
                
                for (y in endY downTo startY) {
                    if (y > topY + 10) continue // Skip air way above terrain
                    
                    val pos = BlockPos(x, y, z)
                    
                    // Basic checks
                    if (pos in failedBlocks) continue
                    val state = world.getBlockState(pos)
                    
                    // ALLOW placement on SNOW LAYERS (any height)
                    var isSnow = state.block == net.minecraft.block.Blocks.SNOW
                    
                    // Check if material is solid-ish
                    if (!state.fluidState.isEmpty && !state.isSolidBlock(world, pos)) continue
                    
                    // If not snow and not solid, skip
                    if (!isSnow && !state.isSolidBlock(world, pos)) continue
                    if (state.isAir) continue
                    
                    val distSq = pos.getSquaredDistance(player.pos)
                    // We want the CLOSEST valid block in this chunk.
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq
                        bestBlock = pos
                    }
                }
            }
        }
        
        return bestBlock
    }
    
    // ... Helpers ...
    
    private fun lookAt(player: net.minecraft.entity.player.PlayerEntity, target: BlockPos) {
        val blockCenter = Vec3d.ofCenter(target)
        val eyePos = player.eyePos
        val dx = blockCenter.x - eyePos.x
        val dy = blockCenter.y - eyePos.y
        val dz = blockCenter.z - eyePos.z
        val horizontalDist = sqrt(dx * dx + dz * dz)
        val targetYaw = Math.toDegrees(kotlin.math.atan2(-dx, dz)).toFloat()
        val targetPitch = Math.toDegrees(kotlin.math.atan2(-dy, horizontalDist)).toFloat().coerceIn(-90f, 90f)
        player.yaw = targetYaw
        player.pitch = targetPitch
    }

    fun invalidateCurrentTarget() {
        cachedTargetBlock?.let { failedBlocks.add(it) }
        cachedTargetBlock = null
        targetRotationDone = false
        stopBreaking()
    }

    private fun canAct(): Boolean = true
    private fun markAction() { lastActionTime = System.currentTimeMillis() }
    
    // Menu helpers
    fun isBurMenuOpen(client: MinecraftClient): Boolean {
        val screen = client.currentScreen ?: return false
        if (screen !is HandledScreen<*>) return false
        val title = screen.title.string
        val config = ModConfig.data.bur
        return title.contains(config.menuTitle, ignoreCase = true)
    }
    
    fun clickBreakAll(client: MinecraftClient): Boolean {
        val screen = client.currentScreen
        if (screen !is GenericContainerScreen) return false
        if (!canAct()) return false
        val handler = screen.screenHandler
        val config = ModConfig.data.bur
        val pattern = Regex(config.breakButtonPattern, RegexOption.IGNORE_CASE)
        for (slot in handler.slots) {
            if (slot.inventory == client.player?.inventory) continue
            val stack = slot.stack
            if (stack.isEmpty) continue
            val displayName = stack.name.string
            val itemId = stack.item.toString()
            if (itemId.contains("netherite_pickaxe") || pattern.containsMatchIn(displayName)) {
                client.interactionManager?.clickSlot(handler.syncId, slot.id, 0, SlotActionType.PICKUP, client.player)
                markAction()
                return true
            }
        }
        if (handler.slots.size > 1 && !handler.slots[1].stack.isEmpty) {
            client.interactionManager?.clickSlot(handler.syncId, 1, 0, SlotActionType.PICKUP, client.player)
            markAction()
            return true
        }
        return false
    }
    
    fun closeMenu(client: MinecraftClient) {
        client.player?.closeHandledScreen()
    }
}
```

## 3. BotController.kt
(Contains StateMachine and orchestration logic)
```kotlin
package com.aquamix.drawbot.automation

import com.aquamix.drawbot.AquamixDrawBot
import com.aquamix.drawbot.anticheat.HumanSimulator
import com.aquamix.drawbot.config.ModConfig
import com.aquamix.drawbot.features.TelegramNotifier
import com.aquamix.drawbot.navigation.RouteOptimizer
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text

/**
 * Главный контроллер бота
 * Управляет всей логикой автоматизации
 * 
 * Улучшено: sealed class FSM, AgenticLoop самокоррекция, анти-чит задержки
 */
class BotController {
    private val stateMachine = StateMachine()
    val chunkBreaker = ChunkBreaker()
    val flightController = FlightController()
    val inventoryManager = InventoryManager()
    
    var isRunning = false
        private set
    
    // Очередь чанков для обработки
    private var chunksQueue: MutableList<ChunkPos> = mutableListOf()
    
    // ... [Initialization & Queue management code omitted for brevity but assumed present] ...
    
    /**
     * Главный цикл бота - вызывается каждый тик
     * Использует sealed class matching для type-safe обработки состояний
     */
    fun tick(client: MinecraftClient) {
        // КЛЮЧЕВОЕ: Baritone-style input replacement
        // Заменяет player.input на BotMovementInput когда бот активен
        com.aquamix.drawbot.input.InputOverrideHandler.onTick(client)
        
        if (!isRunning) return
        
        val player = client.player ?: return
        val config = ModConfig.data
        
        // CRITICAL: Check for falling and auto-recover
        if (flightController.checkAndRecoverFromFall(client)) {
            // Bot is recovering from fall, skip normal state handling
            return
        }
        
        when (val state = stateMachine.currentState) {
            is BotState.Idle -> {
                // Ничего не делаем
            }
            
            is BotState.FlyingToChunk -> {
                
                // Активируем /fly
                flightController.ensureFlyActive(client)
                
                // ALWAYS try to find target block first
                // If chunk is loaded, get target immediately
                if (client.world != null && client.world!!.chunkManager.isChunkLoaded(state.target.x, state.target.z)) {
                    val targetBlock = chunkBreaker.getTarget(client, state.target)
                    if (targetBlock != null) {
                         AquamixDrawBot.LOGGER.info("Direct flight to block ${targetBlock.toShortString()}")
                         stateMachine.transition(BotState.FlyingToBlock(state.target, targetBlock))
                         return
                    }
                }
                
                // Fallback: Fly to chunk center
                // The new flyToChunk handles height automatically (current Y or safe Y)
                if (flightController.flyToChunk(client, state.target)) {
                    stateMachine.transition(BotState.PlacingBur(state.target))
                }
            }
            
            is BotState.FlyingToBlock -> {
                
                flightController.ensureFlyActive(client)
                
                if (flightController.flyToBlock(client, state.targetBlock)) {
                    AquamixDrawBot.LOGGER.debug("Directly arrived at block ${state.targetBlock} in chunk ${state.targetChunk}")
                    // We are at the block, skip Landing state and go straight to placing
                    stateMachine.transition(BotState.PlacingBur(state.targetChunk))
                }
            }
            
            is BotState.PlacingBur -> {
                // Zero-Latency: Check immediately
                val targetBlock = chunkBreaker.getTarget(client, state.target)
                if (targetBlock != null) {
                    // Don't update rotation here, let ChunkBreaker aim
                    flightController.hoverAbove(client, targetBlock, updateRotation = false)
                }
                
                // Speed: GO GO GO
                
                if (chunkBreaker.placeBur(client, state.target)) {
                    stateMachine.transition(BotState.WaitingForMenu(state.target))
                }
                
                // Проверяем если меню уже открылось случайно (лагануло)
                if (chunkBreaker.isBurMenuOpen(client)) {
                    stateMachine.transition(BotState.WaitingForMenu(state.target))
                }
                
                // Таймаут 1 сек (быстро сдаёмся и пробуем снова)
                if (stateMachine.isTimedOut(1000)) {
                    chunkBreaker.reset() // Clear cache and try again
                    
                    // Max 10 retries - if still no valid block, skip this chunk
                    if (state.retryCount >= 10) {
                        // ... Skip logic ...
                    } else {
                        stateMachine.transition(BotState.PlacingBur(state.target, state.retryCount + 1))
                    }
                }
            }
            
            // ... [Menu handling states omitted] ...
        }
    }
}
```

# Expected Output (Deliverable for Kimi K2)
Generate a detailed markdown report entitled **"Aquamix DrawBot: Architectural Analysis & Fix Strategy"**. This report must contain:

1.  **Component Analysis**: Explain how `FlightController`, `ChunkBreaker`, and `BotController` interact (or conflict) to cause the reported spinning and poor pathfinding.
2.  **Specific Logic Flaws**: Point out the exact methods (e.g., `moveTowards`, `findBestTarget`) and lines that need fixing. For example, why does `findBestTarget` return chunk edges? Why does `moveTowards` cause spinning?
3.  **Proposed Algorithms**:
    *   **Better Target Selection**: How to pick the "best" block not just by distance, but by heuristic (centrality, accessibility)?
    *   **PID / Smooth Flight**: How to replace the hard `inputForced` logic with variable speed/easing input?
    *   **Smart Pathing**: How to cache the path properly in `flyToChunk` so it doesn't recalculate every tick or blindly fly into walls?
4.  **Instructions for Kimi**: A bulleted list of "Action Items" that Kimi can follow step-by-step to refactor the code (e.g., "Step 1: Rewrite `findBestTarget` to score blocks based on distance AND distance-to-center...").

**Important**: Your output will be fed directly to the Kimi AI model. Do not simply summarize; provide the *technical blueprint* for the solution.
