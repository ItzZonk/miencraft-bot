package com.aquamix.drawbot.anticheat

import com.aquamix.drawbot.config.ModConfig
import kotlin.random.Random

/**
 * Генератор случайных задержек и отклонений для имитации человека
 * 
 * Основано на исследовании: "Buffering — бот вводит искусственные паузы,
 * имитируя человеческую реакцию"
 * 
 * Использование:
 *   val delay = HumanSimulator.randomDelay(200)  // 200ms ± variation
 *   val jitter = HumanSimulator.mouseJitter()    // случайные микро-повороты
 */
object HumanSimulator {
    
    private val random = Random(System.nanoTime())
    
    /**
     * Генерирует случайную задержку около базового значения
     * 
     * @param baseMs Базовая задержка в миллисекундах
     * @return Случайная задержка в диапазоне [baseMs * (1 - variation), baseMs * (1 + variation)]
     */
    fun randomDelay(baseMs: Long): Long {
        val config = ModConfig.data.antiCheat
        
        if (!config.enabled || !config.randomDelayEnabled) {
            return baseMs
        }
        
        val variation = config.delayVariation
        val minMs = (baseMs * (1.0 - variation)).toLong().coerceAtLeast(1)
        val maxMs = (baseMs * (1.0 + variation)).toLong()
        
        return random.nextLong(minMs, maxMs + 1)
    }
    
    /**
     * Генерирует случайные микро-отклонения мыши (jitter)
     * Имитирует естественную нестабильность человеческой руки
     * 
     * @return Pair<yawDelta, pitchDelta> в градусах
     */
    fun mouseJitter(): Pair<Float, Float> {
        val config = ModConfig.data.antiCheat
        
        if (!config.enabled || !config.mouseJitter) {
            return Pair(0f, 0f)
        }
        
        val maxJitter = config.mouseJitterAmount
        val yawDelta = (random.nextFloat() * 2 - 1) * maxJitter
        val pitchDelta = (random.nextFloat() * 2 - 1) * maxJitter * 0.5f // Pitch меньше
        
        return Pair(yawDelta, pitchDelta)
    }
    
    /**
     * Генерирует случайный коэффициент интерполяции для поворотов
     * Вместо фиксированного значения (0.15f) используем переменное
     * 
     * @param baseFactor Базовый коэффициент (например, 0.15f)
     * @return Случайный коэффициент в диапазоне [baseFactor * 0.7, baseFactor * 1.3]
     */
    fun randomLerpFactor(baseFactor: Float): Float {
        val config = ModConfig.data.antiCheat
        
        if (!config.enabled) {
            return baseFactor
        }
        
        val variation = 0.3f
        val min = baseFactor * (1f - variation)
        val max = baseFactor * (1f + variation)
        
        return random.nextFloat() * (max - min) + min
    }
    
    /**
     * Добавляет случайную паузу между действиями
     * Имитирует время на "размышление" человека
     * 
     * @param minMs Минимальная пауза
     * @param maxMs Максимальная пауза
     * @return Случайная задержка
     */
    fun thinkingDelay(minMs: Long = 50, maxMs: Long = 200): Long {
        val config = ModConfig.data.antiCheat
        
        if (!config.enabled) {
            return minMs
        }
        
        return random.nextLong(minMs, maxMs + 1)
    }
    
    /**
     * Определяет, нужно ли сделать "ошибку" (неточное движение)
     * Люди не идеальны и иногда промахиваются
     * 
     * @param probability Вероятность ошибки (0.0 - 1.0)
     * @return true если нужно сделать ошибку
     */
    fun shouldMakeMistake(probability: Float = 0.05f): Boolean {
        val config = ModConfig.data.antiCheat
        
        if (!config.enabled) {
            return false
        }
        
        return random.nextFloat() < probability
    }
    
    /**
     * Генерирует случайное смещение для "промаха"
     * 
     * @param maxOffset Максимальное смещение
     * @return Случайное смещение
     */
    fun missOffset(maxOffset: Double = 0.5): Double {
        return (random.nextDouble() * 2 - 1) * maxOffset
    }
}
