package com.aquamix.drawbot.render

import com.aquamix.drawbot.AquamixDrawBot
import com.aquamix.drawbot.input.InputOverrideHandler
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.render.RenderTickCounter

/**
 * Отображение статуса бота на экране (HUD)
 * Улучшено: показывает текущие input overrides для отладки
 */
object HudOverlay : HudRenderCallback {
    
    override fun onHudRender(context: DrawContext, tickCounter: RenderTickCounter) {
        val client = MinecraftClient.getInstance()
        val controller = AquamixDrawBot.botController
        
        // Рисуем только если бот запущен или есть чанки в очереди
        if (!controller.isRunning && controller.getQueueSize() == 0) return
        
        val width = client.window.scaledWidth
        val height = client.window.scaledHeight
        
        val x = 10
        val y = height / 2 - 40
        
        // Фон (увеличен для дополнительной информации)
        context.fill(x - 5, y - 5, x + 180, y + 85, 0x80000000.toInt())
        
        // Статус
        val statusColor = if (controller.isRunning) 0x55FF55 else 0xFFAA00
        val statusText = if (controller.isRunning) "§aСтатус: РАБОТАЕТ" else "§6Статус: ОЖИДАНИЕ"
        context.drawText(client.textRenderer, statusText, x, y, statusColor, true)
        
        // Текущее действие (используем displayName из sealed class)
        if (controller.isRunning) {
            val stateName = controller.getCurrentState().displayName
            context.drawText(client.textRenderer, "Действие: $stateName", x, y + 12, 0xFFFFFF, true)
        }
        
        // Строение (Таймер)
        val timerText = "Время: ${controller.getFormattedDuration()}"
        context.drawText(client.textRenderer, timerText, x, y + 26, 0x00FFFF, true) // Cyan
        
        // Статистика
        context.drawText(client.textRenderer, "Осталось: ${controller.getQueueSize()}", x, y + 38, 0xFFFF55, true)
        context.drawText(client.textRenderer, "Выполнено: ${controller.getCompletedChunks().size}", x, y + 50, 0x55FF55, true)
        
        // Текущий чанк
        controller.getTargetChunk()?.let { chunk ->
            context.drawText(client.textRenderer, "Цель: ${chunk.x}, ${chunk.z}", x, y + 50, 0x55FFFF, true)
        }
        
        // === DEBUG: Input Override статус ===
        if (controller.isRunning) {
            val inputsActive = InputOverrideHandler.getDebugState()
            val inputDisplay = if (inputsActive.isEmpty()) "§7(нет)" else "§e$inputsActive"
            context.drawText(client.textRenderer, "Inputs: $inputDisplay", x, y + 64, 0xAAAAAA, true)
            
            // Отображение позиции игрока для отладки
            val player = client.player
            if (player != null) {
                val posStr = String.format("Pos: %.1f, %.1f, %.1f", player.x, player.y, player.z)
                context.drawText(client.textRenderer, posStr, x, y + 76, 0x888888, true)
            }
        }
        // === Minimap HUD ===
        renderMinimap(context, client, controller)
    }
    
    private fun renderMinimap(context: DrawContext, client: MinecraftClient, controller: com.aquamix.drawbot.automation.BotController) {
        val size = 100
        val x = client.window.scaledWidth - size - 10
        val y = 10
        
        // Background
        context.fill(x, y, x + size, y + size, 0xAA000000.toInt())
        // Border
        context.drawBorder(x - 1, y - 1, size + 2, size + 2, 0xFFFFFFFF.toInt())
        
        val player = client.player ?: return
        val playerChunkX = player.chunkPos.x
        val playerChunkZ = player.chunkPos.z
        
        // Mapped radius (chunks)
        val radius = 8
        val dotSize = 4
        
        // Center of minimap
        val cx = x + size / 2
        val cy = y + size / 2
        
        // Scale: size / (radius * 2)
        val scale = size.toDouble() / (radius * 2 + 1).toDouble()
        
        fun drawChunkDot(chunk: com.aquamix.drawbot.automation.ChunkPos, color: Int) {
            val dx = chunk.x - playerChunkX
            val dz = chunk.z - playerChunkZ
            
            if (kotlin.math.abs(dx) > radius || kotlin.math.abs(dz) > radius) return
            
            val px = cx + (dx * scale).toInt()
            val py = cy + (dz * scale).toInt()
            
            context.fill(px - 1, py - 1, px + 2, py + 2, color)
        }
        
        // 1. Completed
        controller.getCompletedChunks().forEach { 
            drawChunkDot(it, 0xFF55FF55.toInt()) // Green
        }
        
        // 2. Queue
        controller.getQueuedChunks().forEach {
            drawChunkDot(it, 0xFFFFAA00.toInt()) // Orange
        }
        
        // 3. Target
        controller.getTargetChunk()?.let {
            drawChunkDot(it, 0xFFFF5555.toInt()) // Red
        }
        
        // 4. Player (Center)
        context.fill(cx - 2, cy - 2, cx + 2, cy + 2, 0xFFFFFFFF.toInt())
        
        // Label
        context.drawText(client.textRenderer, "Minimap", x + 2, y + size - 10, 0xAAAAAA, true)
    }
}
