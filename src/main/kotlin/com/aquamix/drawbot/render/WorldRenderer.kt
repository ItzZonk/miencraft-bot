package com.aquamix.drawbot.render

import com.aquamix.drawbot.AquamixDrawBot
import com.aquamix.drawbot.automation.BotState
import com.aquamix.drawbot.automation.ChunkPos
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.util.math.MatrixStack
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.render.BufferBuilder
import net.minecraft.client.render.Tessellator
import net.minecraft.client.render.VertexFormat
import net.minecraft.client.render.VertexFormats
import net.minecraft.client.render.GameRenderer
import net.minecraft.client.render.BufferRenderer
import org.joml.Matrix4f

/**
 * Улучшенный рендеринг чанков в мире
 * - Текущий целевой чанк: КРАСНЫЙ (яркий, с заливкой)
 * - Чанки в очереди: ОРАНЖЕВЫЙ (следующие 10)
 * - Завершённые чанки: ЗЕЛЁНЫЙ
 * - Рамка на уровне игрока для наглядности
 */
object WorldRenderer : WorldRenderEvents.AfterTranslucent {
    
    // Максимальное количество чанков для отрисовки (производительность)
    private const val MAX_QUEUE_RENDER = 15
    private const val MAX_COMPLETED_RENDER = 30
    
    override fun afterTranslucent(context: WorldRenderContext) {
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return
        val controller = AquamixDrawBot.botController
        
        // Показываем чанки даже если бот остановлен, но есть очередь
        if (controller.getQueueSize() == 0 && controller.getCompletedChunks().isEmpty()) return
        
        val matrices = context.matrixStack() ?: return
        val camera = context.camera()
        val cameraPos = camera.pos
        
        matrices.push()
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)
        
        val playerY = player.y
        val time = System.currentTimeMillis() % 10000L
        
        // Pulse Effect (0.3 to 0.8 alpha)
        val pulse = (kotlin.math.sin(time / 200.0) * 0.5 + 0.5).toFloat() * 0.5f + 0.3f
        
        // Scan Line Effect (moves up and down)
        val scanHeight = (kotlin.math.sin(time / 500.0) * 10.0).toFloat()
        
        // === 1. Завершённые чанки (Зелёные, статик) ===
        val completedChunks = controller.getCompletedChunks().take(MAX_COMPLETED_RENDER)
        for (chunk in completedChunks) {
            renderChunkOutline(matrices, chunk, 0.2f, 0.9f, 0.2f, 0.6f, playerY)
        }
        
        // === 2. Чанки в очереди (Оранжевые) ===
        val queuedChunks = controller.getQueuedChunks()
        val targetChunk = controller.getTargetChunk()
        
        // Пропускаем первый (он же target) и рендерим следующие
        queuedChunks.drop(1).take(MAX_QUEUE_RENDER).forEachIndexed { index, chunk ->
            // Градиент: ближайшие ярче, дальние тусклее
            val alpha = (0.7f - index * 0.03f).coerceAtLeast(0.3f)
            renderChunkOutline(matrices, chunk, 1.0f, 0.6f, 0.0f, alpha, playerY)
        }
        
        // === 3. Текущий целевой чанк (Красный с Анимацией) ===
        if (targetChunk != null && controller.isRunning) {
            // Neon Red/Pink Pulse
            // Fill with pulse alpha
            renderChunkFill(matrices, targetChunk, 1.0f, 0.1f, 0.3f, pulse * 0.4f, playerY)
            
            // Scanner Line
            renderScanLine(matrices, targetChunk, playerY + scanHeight, 1.0f, 0.2f, 0.4f)
            
            // Strong Outline
            renderChunkOutline(matrices, targetChunk, 1.0f, 0.0f, 0.2f, 1.0f, playerY)
        } else if (targetChunk != null) {
            // Бот остановлен, но есть target - показываем синим пульсирующим
            val idlePulse = (kotlin.math.sin(time / 500.0) * 0.5 + 0.5).toFloat() * 0.3f + 0.2f
            renderChunkOutline(matrices, targetChunk, 0.3f, 0.8f, 1.0f, idlePulse, playerY)
        }
        
        // === 4. Первый чанк в очереди (если бот остановлен) ===
        if (!controller.isRunning && queuedChunks.isNotEmpty()) {
            val firstQueued = queuedChunks.first()
            renderChunkOutline(matrices, firstQueued, 1.0f, 1.0f, 0.0f, 0.9f, playerY)
        }

        // === 5. Линия маршрута (Route Line) ===
        if (queuedChunks.isNotEmpty() || targetChunk != null) {
            val routePoints = mutableListOf<net.minecraft.util.math.Vec3d>()
            
            // Start from player
            routePoints.add(player.pos)
            
            // Add current target
            if (targetChunk != null) {
                routePoints.add(net.minecraft.util.math.Vec3d(
                    targetChunk.blockX.toDouble() + 8.0,
                    playerY + 2.0, // Slightly above player
                    targetChunk.blockZ.toDouble() + 8.0
                ))
            }
            
            // Add queued chunks
            queuedChunks.take(MAX_QUEUE_RENDER).forEach { chunk ->
                 routePoints.add(net.minecraft.util.math.Vec3d(
                    chunk.blockX.toDouble() + 8.0,
                    playerY + 2.0,
                    chunk.blockZ.toDouble() + 8.0
                ))
            }
            
            renderRouteLine(matrices, routePoints)
        }
        
        matrices.pop()
    }
    
    /**
     * Рендерит линию маршрута
     */
    private fun renderRouteLine(
        matrices: MatrixStack,
        points: List<net.minecraft.util.math.Vec3d>
    ) {
        if (points.size < 2) return
        
        val matrix = matrices.peek().positionMatrix
        val tessellator = Tessellator.getInstance()
        
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableCull()
        RenderSystem.setShader(GameRenderer::getPositionColorProgram)
        RenderSystem.lineWidth(3.0f)
        
        val buffer = tessellator.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR)
        
        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            
            // Cyan line
            buffer.vertex(matrix, p1.x.toFloat(), p1.y.toFloat(), p1.z.toFloat())
                .color(0.0f, 1.0f, 1.0f, 0.8f)
            buffer.vertex(matrix, p2.x.toFloat(), p2.y.toFloat(), p2.z.toFloat())
                .color(0.0f, 1.0f, 1.0f, 0.8f)
        }
        
        val built = buffer.endNullable()
        if (built != null) {
            BufferRenderer.drawWithGlobalProgram(built)
        }
        
        RenderSystem.enableCull()
        RenderSystem.disableBlend()
    }
    
    /**
     * Рендерит контур чанка (линии по углам + рамка на уровне игрока)
     */
    private fun renderChunkOutline(
        matrices: MatrixStack,
        chunk: ChunkPos,
        r: Float, g: Float, b: Float, a: Float,
        playerY: Double
    ) {
        val minX = chunk.minBlockX.toFloat()
        val minZ = chunk.minBlockZ.toFloat()
        val maxX = chunk.maxBlockX.toFloat() + 1f
        val maxZ = chunk.maxBlockZ.toFloat() + 1f
        
        // Высоты для отрисовки
        val yBottom = (playerY - 10).toFloat().coerceAtLeast(-64f)
        val yTop = (playerY + 30).toFloat().coerceAtMost(320f)
        val yPlayer = playerY.toFloat()
        
        val matrix = matrices.peek().positionMatrix
        
        val tessellator = Tessellator.getInstance()
        
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableCull()
        RenderSystem.setShader(GameRenderer::getPositionColorProgram)
        RenderSystem.lineWidth(2.0f)
        
        val buffer = tessellator.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR)
        
        // Вертикальные линии (4 угла)
        line(buffer, matrix, minX, yBottom, minZ, minX, yTop, minZ, r, g, b, a)
        line(buffer, matrix, maxX, yBottom, minZ, maxX, yTop, minZ, r, g, b, a)
        line(buffer, matrix, maxX, yBottom, maxZ, maxX, yTop, maxZ, r, g, b, a)
        line(buffer, matrix, minX, yBottom, maxZ, minX, yTop, maxZ, r, g, b, a)
        
        // Горизонтальная рамка на уровне игрока
        line(buffer, matrix, minX, yPlayer, minZ, maxX, yPlayer, minZ, r, g, b, a)
        line(buffer, matrix, maxX, yPlayer, minZ, maxX, yPlayer, maxZ, r, g, b, a)
        line(buffer, matrix, maxX, yPlayer, maxZ, minX, yPlayer, maxZ, r, g, b, a)
        line(buffer, matrix, minX, yPlayer, maxZ, minX, yPlayer, minZ, r, g, b, a)
        
        // Рамка снизу
        line(buffer, matrix, minX, yBottom, minZ, maxX, yBottom, minZ, r, g, b, a * 0.5f)
        line(buffer, matrix, maxX, yBottom, minZ, maxX, yBottom, maxZ, r, g, b, a * 0.5f)
        line(buffer, matrix, maxX, yBottom, maxZ, minX, yBottom, maxZ, r, g, b, a * 0.5f)
        line(buffer, matrix, minX, yBottom, maxZ, minX, yBottom, minZ, r, g, b, a * 0.5f)
        
        val built = buffer.endNullable()
        if (built != null) {
            BufferRenderer.drawWithGlobalProgram(built)
        }
        
        RenderSystem.enableCull()
        RenderSystem.disableBlend()
    }
    
    /**
     * Рендерит полупрозрачную заливку чанка
     */
    private fun renderChunkFill(
        matrices: MatrixStack,
        chunk: ChunkPos,
        r: Float, g: Float, b: Float, a: Float,
        playerY: Double
    ) {
        val minX = chunk.minBlockX.toFloat()
        val minZ = chunk.minBlockZ.toFloat()
        val maxX = chunk.maxBlockX.toFloat() + 1f
        val maxZ = chunk.maxBlockZ.toFloat() + 1f
        
        // Dynamic height based on player
        val yBottom = (playerY - 20).toFloat()
        val yTop = (playerY + 40).toFloat()
        
        val matrix = matrices.peek().positionMatrix
        val tessellator = Tessellator.getInstance()
        
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableCull()
        RenderSystem.setShader(GameRenderer::getPositionColorProgram)
        
        val buffer = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR)
        
        // Vertical Pillar Effect (Gradient Alpha)
        // Top cap
        buffer.vertex(matrix, minX, yTop, minZ).color(r, g, b, 0.0f) // Fade out top
        buffer.vertex(matrix, maxX, yTop, minZ).color(r, g, b, 0.0f)
        buffer.vertex(matrix, maxX, yTop, maxZ).color(r, g, b, 0.0f)
        buffer.vertex(matrix, minX, yTop, maxZ).color(r, g, b, 0.0f)
        
        // Center (Solid) - Player Level
        val yMid = playerY.toFloat()
        val alphaMid = a * 0.5f
        
        // Draw sides from Bottom to Top (Two segments for gradient)
        
        // Segment 1: Bottom to Mid (Fade In)
        addQuad(buffer, matrix, minX, maxX, minZ, maxZ, yBottom, yMid, r, g, b, 0.0f, alphaMid)

        // Segment 2: Mid to Top (Fade Out)
        addQuad(buffer, matrix, minX, maxX, minZ, maxZ, yMid, yTop, r, g, b, alphaMid, 0.0f)
        
        // Beacon Core (Inner Beam)
        val beamMinX = minX + 7.0f
        val beamMaxX = maxX - 7.0f
        val beamMinZ = minZ + 7.0f
        val beamMaxZ = maxZ - 7.0f
        
        if (a > 0.5f) { // Only for active target
             addQuad(buffer, matrix, beamMinX, beamMaxX, beamMinZ, beamMaxZ, yBottom, yTop, 1.0f, 1.0f, 1.0f, 0.1f, 0.1f)
        }

        val built = buffer.endNullable()
        if (built != null) {
            BufferRenderer.drawWithGlobalProgram(built)
        }
        
        RenderSystem.enableCull()
        RenderSystem.disableBlend()
    }
    
    // Helper for vertical gradient quads walls
    private fun addQuad(buffer: BufferBuilder, matrix: Matrix4f, x1: Float, x2: Float, z1: Float, z2: Float, y1: Float, y2: Float, r: Float, g: Float, b: Float, a1: Float, a2: Float) {
        // North
        buffer.vertex(matrix, x1, y1, z1).color(r, g, b, a1); buffer.vertex(matrix, x2, y1, z1).color(r, g, b, a1)
        buffer.vertex(matrix, x2, y2, z1).color(r, g, b, a2); buffer.vertex(matrix, x1, y2, z1).color(r, g, b, a2)
        // South
        buffer.vertex(matrix, x1, y1, z2).color(r, g, b, a1); buffer.vertex(matrix, x1, y2, z2).color(r, g, b, a2)
        buffer.vertex(matrix, x2, y2, z2).color(r, g, b, a2); buffer.vertex(matrix, x2, y1, z2).color(r, g, b, a1)
        // West
        buffer.vertex(matrix, x1, y1, z1).color(r, g, b, a1); buffer.vertex(matrix, x1, y2, z1).color(r, g, b, a2)
        buffer.vertex(matrix, x1, y2, z2).color(r, g, b, a2); buffer.vertex(matrix, x1, y1, z2).color(r, g, b, a1)
        // East
        buffer.vertex(matrix, x2, y1, z1).color(r, g, b, a1); buffer.vertex(matrix, x2, y1, z2).color(r, g, b, a1)
        buffer.vertex(matrix, x2, y2, z2).color(r, g, b, a2); buffer.vertex(matrix, x2, y2, z1).color(r, g, b, a2)
    }
    
    private fun renderScanLine(
        matrices: MatrixStack,
        chunk: ChunkPos,
        y: Double,
        r: Float, g: Float, b: Float
    ) {
        val minX = chunk.minBlockX.toFloat()
        val minZ = chunk.minBlockZ.toFloat()
        val maxX = chunk.maxBlockX.toFloat() + 1f
        val maxZ = chunk.maxBlockZ.toFloat() + 1f
        
        val matrix = matrices.peek().positionMatrix
        val tessellator = Tessellator.getInstance()
        
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableCull()
        RenderSystem.setShader(GameRenderer::getPositionColorProgram)
        RenderSystem.lineWidth(4.0f) // Thicker line for scanner
        
        val buffer = tessellator.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR)
        
        val yFloat = y.toFloat()
        
        // Horizontal Quad Frame
        line(buffer, matrix, minX, yFloat, minZ, maxX, yFloat, minZ, r, g, b, 1.0f)
        line(buffer, matrix, maxX, yFloat, minZ, maxX, yFloat, maxZ, r, g, b, 1.0f)
        line(buffer, matrix, maxX, yFloat, maxZ, minX, yFloat, maxZ, r, g, b, 1.0f)
        line(buffer, matrix, minX, yFloat, maxZ, minX, yFloat, minZ, r, g, b, 1.0f)
        
        val built = buffer.endNullable()
        if (built != null) {
            BufferRenderer.drawWithGlobalProgram(built)
        }
        
        RenderSystem.enableCull()
        RenderSystem.disableBlend()
    }
    
    private fun line(
        buffer: BufferBuilder,
        matrix: Matrix4f,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        buffer.vertex(matrix, x1, y1, z1).color(r, g, b, a)
        buffer.vertex(matrix, x2, y2, z2).color(r, g, b, a)
    }
}
