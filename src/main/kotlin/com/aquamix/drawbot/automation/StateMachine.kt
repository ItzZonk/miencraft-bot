package com.aquamix.drawbot.automation

import com.aquamix.drawbot.AquamixDrawBot

/**
 * Sealed interface для состояний бота (FSM Architecture)
 * Каждое состояние хранит свои данные, что обеспечивает type-safety.
 * 
 * Основано на исследовании: "Каждое состояние — независимый класс"
 */
sealed interface BotState {
    val displayName: String
    
    /** Бот неактивен, ожидает команды */
    object Idle : BotState {
        override val displayName = "Ожидание"
    }
    
    /** Полёт к целевому чанку */
    data class FlyingToChunk(val target: ChunkPos) : BotState {
        override val displayName = "Полёт к чанку"
    }

    /** Полёт к конкретному блоку (оптимизация) */
    data class FlyingToBlock(val targetChunk: ChunkPos, val targetBlock: net.minecraft.util.math.BlockPos) : BotState {
        override val displayName = "Полёт к точке"
    }
    
    /** Приземление в целевом чанке */
    data class Landing(val target: ChunkPos) : BotState {
        override val displayName = "Приземление"
    }
    
    /** Установка БУРа (End Portal Frame) */
    data class PlacingBur(val target: ChunkPos, val retryCount: Int = 0) : BotState {
        override val displayName = "Установка БУРа"
    }
    
    /** Ожидание появления меню БУРа */
    data class WaitingForMenu(val target: ChunkPos) : BotState {
        override val displayName = "Ожидание меню"
    }
    
    /** Клик по кнопке "Сломать все" в меню */
    data class ClickingMenu(val target: ChunkPos) : BotState {
        override val displayName = "Клик в меню"
    }
    
    /** Ожидание подтверждения от сервера */
    data class WaitingConfirmation(
        val target: ChunkPos, 
        val confirmed: Boolean = false
    ) : BotState {
        override val displayName = "Ожидание подтверждения"
    }
    
    /** Переход к следующему чанку */
    data class MovingToNext(val completedChunk: ChunkPos) : BotState {
        override val displayName = "Переход к следующему"
    }
    
    /** Подъем на безопасную высоту после падения */
    data class Ascending(val fromChunk: ChunkPos, val targetHeight: Double = 120.0) : BotState {
        override val displayName = "Подъем на высоту"
    }
    
    /** 
     * Состояние самокоррекции (Agentic Loop)
     * Бот анализирует ошибку и пытается восстановиться
     */
    data class SelfHealing(
        val error: BotError,
        val previousState: BotState,
        val healingAttempt: Int = 1
    ) : BotState {
        override val displayName = "Самокоррекция"
    }
}

/**
 * Типы ошибок для агентского цикла самокоррекции
 */
sealed interface BotError {
    val message: String
    
    /** Таймаут операции */
    data class Timeout(val operation: String, val durationMs: Long) : BotError {
        override val message = "Таймаут: $operation (${durationMs}ms)"
    }
    
    /** Меню не найдено */
    data class MenuNotFound(val expectedTitle: String) : BotError {
        override val message = "Меню не найдено: $expectedTitle"
    }
    
    /** Кнопка не найдена в меню */
    data class ButtonNotFound(val pattern: String) : BotError {
        override val message = "Кнопка не найдена: $pattern"
    }
    
    /** Ошибка полёта */
    data class FlightFailed(val reason: String) : BotError {
        override val message = "Ошибка полёта: $reason"
    }
    
    /** Нет БУРа в инвентаре */
    object NoBurInInventory : BotError {
        override val message = "Нет БУРа в инвентаре"
    }
    
    /** Застревание (игрок не двигается) */
    data class Stuck(val position: String, val durationMs: Long) : BotError {
        override val message = "Застрял в $position на ${durationMs}ms"
    }
}

/**
 * Конечный автомат для управления состояниями бота
 * Улучшенная версия с sealed classes и агентским циклом
 */
class StateMachine {
    var currentState: BotState = BotState.Idle
        private set
    
    var stateStartTime: Long = System.currentTimeMillis()
        private set
    
    /**
     * Переход в новое состояние
     */
    fun transition(newState: BotState) {
        if (currentState != newState) {
            AquamixDrawBot.LOGGER.debug("State: ${currentState.displayName} -> ${newState.displayName}")
            currentState = newState
            stateStartTime = System.currentTimeMillis()
        }
    }
    
    /**
     * Сколько миллисекунд в текущем состоянии
     */
    fun timeInState(): Long = System.currentTimeMillis() - stateStartTime
    
    /**
     * Проверка таймаута состояния
     */
    fun isTimedOut(timeoutMs: Long): Boolean = timeInState() > timeoutMs
    
    /**
     * Полный сброс машины состояний
     */
    fun reset() {
        currentState = BotState.Idle
        stateStartTime = System.currentTimeMillis()
    }
    
    /**
     * Проверка, находимся ли в процессе работы
     */
    fun isWorking(): Boolean = currentState !is BotState.Idle
    
    /**
     * Получить текущий целевой чанк (если есть)
     */
    fun getTargetChunk(): ChunkPos? = when (val state = currentState) {
        is BotState.FlyingToChunk -> state.target
        is BotState.FlyingToBlock -> state.targetChunk
        is BotState.Landing -> state.target
        is BotState.PlacingBur -> state.target
        is BotState.WaitingForMenu -> state.target
        is BotState.ClickingMenu -> state.target
        is BotState.WaitingConfirmation -> state.target
        is BotState.MovingToNext -> state.completedChunk
        is BotState.Ascending -> state.fromChunk
        is BotState.SelfHealing -> getTargetFromState(state.previousState)
        is BotState.Idle -> null
    }
    
    private fun getTargetFromState(state: BotState): ChunkPos? = when (state) {
        is BotState.FlyingToChunk -> state.target
        is BotState.FlyingToBlock -> state.targetChunk
        is BotState.Landing -> state.target
        is BotState.PlacingBur -> state.target
        is BotState.WaitingForMenu -> state.target
        is BotState.ClickingMenu -> state.target
        is BotState.WaitingConfirmation -> state.target
        is BotState.MovingToNext -> state.completedChunk
        else -> null
    }
}
