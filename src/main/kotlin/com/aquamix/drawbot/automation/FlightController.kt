package com.aquamix.drawbot.automation

import com.aquamix.drawbot.AquamixDrawBot
import com.aquamix.drawbot.config.ModConfig
import com.aquamix.drawbot.input.BotInput
import com.aquamix.drawbot.input.InputOverrideHandler
import net.minecraft.client.MinecraftClient
import net.minecraft.util.math.Vec3d
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Контроллер полёта на элитрах с командой /fly
 * Использует Baritone-style InputOverrideHandler для управления.
 */
class FlightController {
    private var lastFlyCommand = 0L
    private var flyCommandCooldown = 2000L
    
    // Debug helper
    private var lastInputCheck = 0L
    
    private var isFlying = false
    private var wasOnGround = true
    
    /**
     * Убедиться что /fly активен
     */
    fun ensureFlyActive(client: MinecraftClient) {
        val player = client.player ?: return
        
        if (player.abilities.allowFlying && !player.abilities.flying) {
            if (!client.options.jumpKey.isPressed) {
                player.abilities.flying = true
                player.sendAbilitiesUpdate()
            }
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
        
        AquamixDrawBot.LOGGER.debug("Отправлена команда /fly")
    }
    
    private fun canSendFlyCommand(): Boolean {
        return System.currentTimeMillis() - lastFlyCommand > flyCommandCooldown
    }
    
    /**
     * Двигаться к целевой точке
     * @return true если достигли цели
     */
    fun moveTowards(client: MinecraftClient, targetX: Double, targetY: Double, targetZ: Double): Boolean {
        val player = client.player ?: return false
        
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
        
        // --- LOOK AT TARGET ---
        val targetYaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
        val targetPitch = Math.toDegrees(atan2(-dy, horizontalDist)).toFloat()
            .coerceIn(-60f, 60f)
        
        player.yaw = lerpAngle(player.yaw, targetYaw, 0.15f)
        player.pitch = lerpAngle(player.pitch, targetPitch, 0.1f)
        player.isSprinting = true

        // --- BARITONE-STYLE INPUT ---
        InputOverrideHandler.setInputForced(BotInput.MOVE_FORWARD, true)
        InputOverrideHandler.setInputForced(BotInput.SPRINT, true)
        
        // Obstacle Avoidance: Auto-jump if hitting a wall
        if (player.horizontalCollision) {
             InputOverrideHandler.setInputForced(BotInput.JUMP, true)
        }
        
        // Vertical control
        when {
            dy > 0.5 -> {
                InputOverrideHandler.setInputForced(BotInput.JUMP, true)
                InputOverrideHandler.setInputForced(BotInput.SNEAK, false)
            }
            dy < -0.5 -> {
                // Determine if we should descend carefully
                // If there is a block right below us, we can just fall (unless we want to sneak)
                // But for flight, sneaking usually means "go down"
                InputOverrideHandler.setInputForced(BotInput.JUMP, false)
                InputOverrideHandler.setInputForced(BotInput.SNEAK, true)
            }
            else -> {
                InputOverrideHandler.setInputForced(BotInput.JUMP, false)
                InputOverrideHandler.setInputForced(BotInput.SNEAK, false)
            }
        }
        
        return false
    }

    /**
     * Лететь к чанку на заданной высоте
     */
    fun flyToChunk(client: MinecraftClient, chunk: ChunkPos): Boolean {
        val flightHeight = ModConfig.data.navigation.flightHeight.toDouble()
        val player = client.player ?: return false
        
        val targetX = chunk.blockX.toDouble()
        val targetZ = chunk.blockZ.toDouble()
        val dx = targetX - player.x
        val dz = targetZ - player.z
        val horizontalDist = sqrt(dx * dx + dz * dz)
        
        player.isSprinting = true
        
        // --- OBSTACLE AVOIDANCE ---
        if (player.horizontalCollision) {
            InputOverrideHandler.setInputForced(BotInput.JUMP, true)
        }
        
        // --- SMART FLIGHT: Skip high altitude for nearby chunks ---
        if (horizontalDist < 64) {
            return moveTowards(client, targetX, player.y, targetZ)
        }
        // ... rest of method ...
        
        // --- PHASE 1: Ascent + Forward (Far Distance) ---
        if (player.y < flightHeight - 2) {
            InputOverrideHandler.setInputForced(BotInput.JUMP, true)
            InputOverrideHandler.setInputForced(BotInput.SNEAK, false)
            
            if (horizontalDist > 5) {
                moveTowards(client, targetX, player.y, targetZ)
                InputOverrideHandler.setInputForced(BotInput.JUMP, true)
            } else {
                InputOverrideHandler.setInputForced(BotInput.MOVE_FORWARD, false)
            }
            
            player.pitch = -30f
            return false
        }
        
        // --- PHASE 2: Horizontal Flight ---
        InputOverrideHandler.setInputForced(BotInput.JUMP, false)
        
        val threshold = ModConfig.data.navigation.arrivalThreshold
        if (horizontalDist < threshold) {
            stopMovement(client)
            return true
        }
        
        return moveTowards(client, targetX, flightHeight, targetZ)
    }
    
    /**
     * Приземлиться в чанке
     * Target: STAND 2 BLOCKS AWAY from center to place IN FRONT
     */
    fun landInChunk(client: MinecraftClient, chunk: ChunkPos): Boolean {
        val player = client.player ?: return false
        val world = client.world ?: return false
        
        // Offset target by 2 blocks on X axis so we land "in front" of the center
        // Center is +0.5. We want to stand at +2.5 (2 blocks away)
        val targetX = chunk.blockX.toDouble() + 0.5 + 2.0
        val targetZ = chunk.blockZ.toDouble() + 0.5
        
        var groundY = 64
        for (y in player.blockY downTo world.bottomY) {
            // Check ground at the LANDING spot (targetX), not center
            val pos = net.minecraft.util.math.BlockPos(targetX.toInt(), y, targetZ.toInt())
            val block = world.getBlockState(pos)
            if (!block.isAir && !block.isLiquid) {
                groundY = y + 1
                break
            }
        }
        
        val targetY = groundY.toDouble() + 0.1
        
        if (player.isOnGround) {
            stopMovement(client)
            isFlying = false
            return true
        }
        
        // If very close to ground but not "on ground" (e.g. hovering), force sneak/descend
        if (player.y - groundY < 2.0) {
            InputOverrideHandler.setInputForced(BotInput.SNEAK, true)
            InputOverrideHandler.setInputForced(BotInput.JUMP, false)
            return false
        }
        
        val dy = targetY - player.y
        if (dy < -0.5) {
            InputOverrideHandler.setInputForced(BotInput.SNEAK, true)
            InputOverrideHandler.setInputForced(BotInput.JUMP, false)
        } else {
            InputOverrideHandler.setInputForced(BotInput.SNEAK, false)
        }
        
        // Add obstacle avoidance here too
        if (player.horizontalCollision) {
             InputOverrideHandler.setInputForced(BotInput.JUMP, true)
        }
        
        return moveTowards(client, targetX, targetY, targetZ)
    }
    
    /**
     * Остановить всё движение (Baritone-style: clear all forced inputs)
     */
    fun stopMovement(client: MinecraftClient) {
        InputOverrideHandler.clearAll()
        
        // Also reset vanilla keys as failsafe
        val options = client.options
        options.forwardKey.isPressed = false
        options.backKey.isPressed = false
        options.leftKey.isPressed = false
        options.rightKey.isPressed = false
        options.jumpKey.isPressed = false
        options.sneakKey.isPressed = false
    }
    
    private fun lerpAngle(from: Float, to: Float, t: Float): Float {
        var diff = to - from
        while (diff < -180) diff += 360
        while (diff > 180) diff -= 360
        return from + diff * t
    }
    
    fun reset() {
        isFlying = false
        wasOnGround = true
        InputOverrideHandler.clearAll()
    }
}

