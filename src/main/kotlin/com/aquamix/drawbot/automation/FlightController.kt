package com.aquamix.drawbot.automation

import com.aquamix.drawbot.AquamixDrawBot
import com.aquamix.drawbot.config.ModConfig
import net.minecraft.client.MinecraftClient
import net.minecraft.util.math.Vec3d
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Контроллер полёта на элитрах с командой /fly
 */
class FlightController {
    private var lastFlyCommand = 0L
    private var flyCommandCooldown = 2000L // Не спамить командой
    
    private var isFlying = false
    private var wasOnGround = true
    
    /**
     * Убедиться что /fly активен
     * Вызывается каждый тик при полёте
     */
    fun ensureFlyActive(client: MinecraftClient) {
        val player = client.player ?: return
        
        // 1. Force capabilities if allowed (Creative/God mode)
        if (player.abilities.allowFlying && !player.abilities.flying) {
            // Если режим полета разрешен, но мы не летим - пробуем взлететь
            // Для этого нужно нажать прыжок дважды
             if (!client.options.jumpKey.isPressed) {
                 // Симуляция двойного нажатия (через флаг abilities.flying это самый надежный способ если allowFlying=true)
                 player.abilities.flying = true 
                 player.sendAbilitiesUpdate()
             }
            isFlying = true
        }
        
        // 2. If not allowed, try /fly command
        if (!player.abilities.allowFlying && !isFlying && canSendFlyCommand()) {
             sendFlyCommand(client)
             // После команды ждём немного и пробуем взлететь
        }
        
        // 3. Track ground state
        if (player.isOnGround && !wasOnGround && isFlying) {
             // Landed logic check
             if (!player.abilities.allowFlying) isFlying = false
        }
        wasOnGround = player.isOnGround
    }
    
    /**
     * Отправить команду /fly
     */
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
    /**
     * Двигаться к целевой точке
     * @return true если достигли цели
     */
    fun moveTowards(client: MinecraftClient, targetX: Double, targetY: Double, targetZ: Double): Boolean {
        val player = client.player ?: return false
        val options = client.options
        
        val dx = targetX - player.x
        val dy = targetY - player.y
        val dz = targetZ - player.z
        
        val horizontalDist = sqrt(dx * dx + dz * dz)
        val totalDist = sqrt(dx * dx + dy * dy + dz * dz)
        
        val threshold = ModConfig.data.navigation.arrivalThreshold
        
        // Проверка достижения цели
        if (totalDist < threshold) {
            stopMovement(client)
            return true
        }
        
        // Расчёт yaw (горизонтальный угол)
        val targetYaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
        
        // Расчёт pitch (вертикальный угол)
        val targetPitch = Math.toDegrees(atan2(-dy, horizontalDist)).toFloat()
            .coerceIn(-60f, 60f) // Ограничиваем угол
        
        // Плавный поворот
        player.yaw = lerpAngle(player.yaw, targetYaw, 0.15f)
        player.pitch = lerpAngle(player.pitch, targetPitch, 0.1f)
        
        // Управление движением
        options.forwardKey.isPressed = true
        player.isSprinting = true // ВСЕГДА СПРИНТ
        
        // Force input on player entity directly (bypasses some input polling issues)
        player.input?.pressingForward = true
        
        // Если нужно подняться/опуститься - ДЕРЖИМ клавишу пока не достигнем высоты
        // Убрали порог в 2 блока, теперь любое отклонение вызывает корректировку
        if (dy > 0.5) {
            options.jumpKey.isPressed = true  // Пробел (вверх)
            options.sneakKey.isPressed = false
        } else if (dy < -0.5) {
            options.jumpKey.isPressed = false
            options.sneakKey.isPressed = true // Shift (вниз)
        } else {
            options.jumpKey.isPressed = false
            options.sneakKey.isPressed = false
        }
        
        return false
    }

    /**
     * Лететь к чанку на заданной высоте
     * @return true если достигли позиции над чанком
     */
    /**
     * Лететь к чанку на заданной высоте
     * @return true если достигли позиции над чанком
     */
    fun flyToChunk(client: MinecraftClient, chunk: ChunkPos): Boolean {
        val flightHeight = ModConfig.data.navigation.flightHeight.toDouble()
        val player = client.player ?: return false
        val options = client.options
        
        val targetX = chunk.blockX.toDouble()
        val targetZ = chunk.blockZ.toDouble()
        val dx = targetX - player.x
        val dz = targetZ - player.z
        val horizontalDist = sqrt(dx * dx + dz * dz)
        
        // Включаем спринт для скорости
        player.isSprinting = true
        
        // --- УМНЫЙ ПОЛЁТ ---
        // Если цель близко (соседний чанк, < 64 блоков), летим напрямую без подъема в космос
        if (horizontalDist < 64) {
            return moveTowards(client, targetX, player.y, targetZ)
        }
        
        // --- ФАЗА 1: Подъём + Полёт (Дальняя дистанция) ---
        // Единая логика: если низко - летим вверх, но при этом двигаемся к цели
        
        if (player.y < flightHeight - 2) {
            options.jumpKey.isPressed = true  // ДЕРЖИМ ПРОБЕЛ
            options.sneakKey.isPressed = false
            
            // Если далеко от цели по горизонтали - жмём вперед
            // Чтобы не просто висели, а летели по диагонали
            if (horizontalDist > 5) {
                 moveTowards(client, targetX, player.y, targetZ) // Используем player.y чтобы не сбивать pitch
                 // Overwrite jump key from moveTowards logic just in case
                 options.jumpKey.isPressed = true 
            } else {
                 options.forwardKey.isPressed = false
            }
            
            // Смотрим вверх для ускорения (немного, чтобы лететь вперед-вверх)
            // Но moveTowards уже ставит pitch. 
            // Если мы используем moveTowards, он поставит pitch на 0 (горизонт).
            // Нам нужно принудительно поднять взгляд.
            player.pitch = -30f 
            
            return false
        }
        
        // --- ФАЗА 2: Горизонтальный полёт ---
        options.jumpKey.isPressed = false
        
        // Проверка достижения цели
        val threshold = ModConfig.data.navigation.arrivalThreshold
        if (horizontalDist < threshold) {
            stopMovement(client)
            return true // Достигли!
        }
        
        // Летим к цели
        return moveTowards(client, targetX, flightHeight, targetZ)
    }
    
    /**
     * Приземлиться в чанке
     * @return true если приземлились достаточно близко для установки блока
     */
    fun landInChunk(client: MinecraftClient, chunk: ChunkPos): Boolean {
        val player = client.player ?: return false
        val world = client.world ?: return false
        val options = client.options
        
        val targetX = chunk.blockX.toDouble() + 0.5 // Центр блока
        val targetZ = chunk.blockZ.toDouble() + 0.5
        
        // Найти высоту земли (сканируем от игрока вниз)
        var groundY = 64
        for (y in player.blockY downTo world.bottomY) {
            val pos = net.minecraft.util.math.BlockPos(chunk.blockX, y, chunk.blockZ)
            val block = world.getBlockState(pos)
            if (!block.isAir && !block.isLiquid) {
                groundY = y + 1
                break
            }
        }
        
        // Целевая высота - прямо над землей (чтобы можно было ставить блок)
        val targetY = groundY.toDouble() + 0.1
        
        // Если достаточно близко к земле - приземлились
        if (player.isOnGround || player.y - groundY < 1.0) {
            stopMovement(client)
            isFlying = false
            return true
        }
        
        // Спускаемся - ДЕРЖИМ SHIFT для спуска
        val dy = targetY - player.y
        if (dy < -0.5) {
            options.sneakKey.isPressed = true  // ДЕРЖИМ SHIFT
            options.jumpKey.isPressed = false
        } else {
            options.sneakKey.isPressed = false
        }
        
        // Также двигаемся к центру чанка горизонтально
        return moveTowards(client, targetX, targetY, targetZ)
    }
    
    /**
     * Остановить всё движение
     */
    fun stopMovement(client: MinecraftClient) {
        val options = client.options
        options.forwardKey.isPressed = false
        options.backKey.isPressed = false
        options.leftKey.isPressed = false
        options.rightKey.isPressed = false
        options.jumpKey.isPressed = false
        options.sneakKey.isPressed = false
    }
    
    /**
     * Линейная интерполяция углов с учётом перехода через 360°
     */
    private fun lerpAngle(from: Float, to: Float, t: Float): Float {
        var diff = to - from
        while (diff < -180) diff += 360
        while (diff > 180) diff -= 360
        return from + diff * t
    }
    
    /**
     * Сброс состояния контроллера
     */
    fun reset() {
        isFlying = false
        wasOnGround = true
    }
}
