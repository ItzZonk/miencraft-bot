package com.aquamix.drawbot.automation

import kotlinx.serialization.Serializable

/**
 * Координаты чанка (не блока!)
 * Чанк 16x16 блоков, координаты чанка = координаты блока / 16
 */
@Serializable
data class ChunkPos(val x: Int, val z: Int) {
    
    /** X координата центра чанка в блоках */
    val blockX: Int get() = x * 16 + 8
    
    /** Z координата центра чанка в блоках */
    val blockZ: Int get() = z * 16 + 8
    
    /** Левый верхний угол чанка */
    val minBlockX: Int get() = x * 16
    val minBlockZ: Int get() = z * 16
    
    /** Правый нижний угол чанка */
    val maxBlockX: Int get() = x * 16 + 15
    val maxBlockZ: Int get() = z * 16 + 15
    
    /**
     * Евклидово расстояние до другого чанка
     */
    fun distanceTo(other: ChunkPos): Double {
        val dx = (other.x - x).toDouble()
        val dz = (other.z - z).toDouble()
        return kotlin.math.sqrt(dx * dx + dz * dz)
    }
    
    /**
     * Манхэттенское расстояние (для быстрой оценки)
     */
    fun manhattanDistanceTo(other: ChunkPos): Int {
        return kotlin.math.abs(other.x - x) + kotlin.math.abs(other.z - z)
    }
    
    /**
     * Проверяет, находится ли блок в этом чанке
     */
    fun containsBlock(blockX: Int, blockZ: Int): Boolean {
        return blockX >= minBlockX && blockX <= maxBlockX &&
               blockZ >= minBlockZ && blockZ <= maxBlockZ
    }
    
    override fun toString(): String = "Chunk($x, $z)"
    
    companion object {
        /**
         * Создаёт ChunkPos из координат блока
         */
        fun fromBlockPos(blockX: Int, blockZ: Int): ChunkPos {
            return ChunkPos(blockX shr 4, blockZ shr 4)
        }
    }
}
