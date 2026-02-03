package com.aquamix.drawbot.render

import com.aquamix.drawbot.AquamixDrawBot
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.render.RenderTickCounter

/**
 * Отображение статуса бота на экране (HUD)
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
        val y = height / 2 - 20
        
        // Фон
        context.fill(x - 5, y - 5, x + 150, y + 55, 0x80000000.toInt())
        
        // Статус
        val statusColor = if (controller.isRunning) 0x55FF55 else 0xFFAA00
        val statusText = if (controller.isRunning) "Статус: РАБОТАЕТ" else "Статус: ОЖИДАНИЕ"
        context.drawText(client.textRenderer, statusText, x, y, statusColor, true)
        
        // Текущее действие
        if (controller.isRunning) {
            context.drawText(client.textRenderer, "Действие: ${controller.getCurrentState()}", x, y + 10, 0xFFFFFF, true)
        }
        
        // Статистика
        context.drawText(client.textRenderer, "Осталось чанков: ${controller.getQueueSize()}", x, y + 25, 0xFFFF55, true)
        context.drawText(client.textRenderer, "Выполнено: ${controller.getCompletedChunks().size}", x, y + 35, 0x55FF55, true)
        
        // Текущий чанк
        controller.getTargetChunk()?.let { chunk ->
            context.drawText(client.textRenderer, "Лечу к: ${chunk.x}, ${chunk.z}", x, y + 45, 0x55FFFF, true)
        }
    }
}
