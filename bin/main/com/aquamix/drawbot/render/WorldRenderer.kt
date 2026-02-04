package com.aquamix.drawbot.render

import com.aquamix.drawbot.AquamixDrawBot
import com.aquamix.drawbot.automation.ChunkPos
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.math.RotationAxis
import org.joml.Matrix4f

/**
 * Рендеринг в мире (подсветка чанков)
 */
object WorldRenderer : WorldRenderEvents.AfterTranslucent {
    
    override fun afterTranslucent(context: WorldRenderContext) {
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return
        val controller = AquamixDrawBot.botController
        
        if (!controller.isRunning && controller.getQueueSize() == 0) return
        
        val matrices = context.matrixStack() ?: return
        val camera = context.camera()
        val cameraPos = camera.pos
        
        matrices.push()
        // Смещаем мир относительно камеры
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)
        
        val consumers = context.consumers() ?: return
        
        // 1. Текущий целевой чанк (Красный)
        controller.getTargetChunk()?.let { chunk ->
            renderChunkBox(matrices, consumers, chunk, 1.0f, 0.0f, 0.0f, 0.5f) // Red
            // renderChunkOverlay(matrices, consumers, chunk, 1.0f, 0.0f, 0.0f, 0.2f)
        }
        
        // 2. Следующий чанк в очереди (Желтый)
        controller.getQueuedChunks().firstOrNull()?.let { chunk ->
             // Только если он не совпадает с текущим
            if (chunk != controller.getTargetChunk()) {
                renderChunkBox(matrices, consumers, chunk, 1.0f, 1.0f, 0.0f, 0.5f) // Yellow
            }
        }
        
        matrices.pop()
    }
    
    private fun renderChunkBox(
        matrices: MatrixStack,
        consumers: VertexConsumerProvider,
        chunk: ChunkPos,
        r: Float, g: Float, b: Float, a: Float
    ) {
        val minX = chunk.minBlockX.toDouble()
        val minZ = chunk.minBlockZ.toDouble()
        val maxX = chunk.maxBlockX.toDouble() + 1.0
        val maxZ = chunk.maxBlockZ.toDouble() + 1.0
        val yMin = -64.0
        val yMax = 320.0
        
        val buffer = consumers.getBuffer(RenderLayer.getLines())
        
        // Вертикальные линии (углы)
        val matrix = matrices.peek().positionMatrix
        
        fun line(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double) {
            buffer.vertex(matrix, x1.toFloat(), y1.toFloat(), z1.toFloat()).color(r, g, b, a).normal(0f, 1f, 0f)
            buffer.vertex(matrix, x2.toFloat(), y2.toFloat(), z2.toFloat()).color(r, g, b, a).normal(0f, 1f, 0f)
        }
        
        // 4 угла
        line(minX, yMin, minZ, minX, yMax, minZ)
        line(maxX, yMin, minZ, maxX, yMax, minZ)
        line(maxX, yMin, maxZ, maxX, yMax, maxZ)
        line(minX, yMin, maxZ, minX, yMax, maxZ)
        
        // Рамка на высоте игрока (для наглядности)
        val playerY = MinecraftClient.getInstance().player?.y ?: 100.0
        // line(minX, playerY, minZ, maxX, playerY, minZ) ...
    }
    
    private fun renderChunkOverlay(
        matrices: MatrixStack, 
        consumers: VertexConsumerProvider, 
        chunk: ChunkPos,
        r: Float, g: Float, b: Float, a: Float
    ) {
        // Можно добавить полупрозрачный столб, если нужно
    }
}
