package com.aquamix.drawbot.render

import com.aquamix.drawbot.automation.ChunkPos
import net.minecraft.block.MapColor
import net.minecraft.client.MinecraftClient
import net.minecraft.client.texture.NativeImage
import net.minecraft.client.texture.NativeImageBackedTexture
import net.minecraft.util.Identifier
import net.minecraft.world.Heightmap

/**
 * Глобальный кэш текстур карты (Fog of War)
 * Позволяет видеть чанки, которые были выгружены, но уже посещены.
 */
object MapTerrainCache {
    private val textures = mutableMapOf<ChunkPos, NativeImageBackedTexture>()
    private val identifiers = mutableMapOf<ChunkPos, Identifier>()

    /**
     * Получить идентификатор текстуры.
     * Если текстура уже есть - возвращаем её (даже если чанк выгружен).
     * Если нет и чанк загружен - генерируем.
     * Если нет и чанк выгружен - возвращаем null.
     */
    fun getTextureId(client: MinecraftClient, chunkX: Int, chunkZ: Int): Identifier? {
        val pos = ChunkPos(chunkX, chunkZ)

        // 1. Возвращаем кэш если есть
        if (identifiers.containsKey(pos)) {
            return identifiers[pos]
        }

        // 2. Проверяем загружен ли чанк для генерации
        val world = client.world ?: return null
        if (world.chunkManager.isChunkLoaded(chunkX, chunkZ)) {
            return generateTexture(client, pos)
        }

        // 3. Чанк не виден и не в кэше
        return null
    }

    private fun generateTexture(client: MinecraftClient, pos: ChunkPos): Identifier {
        val img = NativeImage(16, 16, true)
        val world = client.world!!
        val heights = IntArray(256)
        
        // 1. Сначала собираем высоты для теней
        for (z in 0 until 16) {
            for (x in 0 until 16) {
                val worldX = pos.x * 16 + x
                val worldZ = pos.z * 16 + z
                heights[z * 16 + x] = world.getTopY(Heightmap.Type.WORLD_SURFACE, worldX, worldZ)
            }
        }
        
        // 2. Генерируем пиксели
        for (z in 0 until 16) {
            for (x in 0 until 16) {
                val idx = z * 16 + x
                val h = heights[idx]
                
                val worldX = pos.x * 16 + x
                val worldZ = pos.z * 16 + z
                val blockPos = net.minecraft.util.math.BlockPos(worldX, h - 1, worldZ)
                
                val state = world.getBlockState(blockPos)
                val mapColor = state.getMapColor(world, blockPos)
                
                // Базовый цвет
                if (mapColor == MapColor.CLEAR) {
                    img.setColor(x, z, 0) // Прозрачный
                    continue
                }
                
                var color = mapColor.color
                
                // --- Effects ---
                
                // 1. Shading (Тени от высоты)
                // Сравниваем с блоком выше (z-1)
                var shading = 0
                if (z > 0) {
                    val hAbove = heights[(z - 1) * 16 + x]
                    if (h < hAbove) shading = -15 // Темнее
                    if (h > hAbove) shading = 15 // Светлее
                }
                
                // Применяем шейдинг
                val r = ((color shr 16) and 0xFF) + shading
                val g = ((color shr 8) and 0xFF) + shading
                val b = (color and 0xFF) + shading
                
                // Clamp
                val finalR = r.coerceIn(0, 255)
                val finalG = g.coerceIn(0, 255)
                val finalB = b.coerceIn(0, 255)
                
                // Alpha = 255 (Fully Opaque)
                var alpha = 0xFF
                
                // Water Transparency
                if (mapColor == MapColor.OAK_TAN || mapColor == MapColor.WATER_BLUE || mapColor.id == 12) {
                     alpha = 0xAA // Semi transparency
                }
                
                val finalColor = (alpha shl 24) or (finalB shl 16) or (finalG shl 8) or finalR
                
                img.setColor(x, z, finalColor)
            }
        }
        
        val texture = NativeImageBackedTexture(img)
        textures[pos] = texture
        val id = client.textureManager.registerDynamicTexture("chunk_${pos.x}_${pos.z}", texture)
        identifiers[pos] = id
        return id
    }
    
    fun clear(client: MinecraftClient) {
        identifiers.forEach { (_, id) -> 
             client.textureManager.destroyTexture(id)
        }
        textures.forEach { (_, tex) -> tex.close() }
        textures.clear()
        identifiers.clear()
    }
}
