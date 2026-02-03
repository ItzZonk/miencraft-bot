package com.aquamix.drawbot.automation

import com.aquamix.drawbot.AquamixDrawBot

/**
 * Состояния бота
 */
enum class BotState(val displayName: String) {
    IDLE("Ожидание"),
    FLYING_TO_CHUNK("Полёт к чанку"),
    LANDING("Приземление"),
    PLACING_BUR("Установка БУРа"),
    WAITING_FOR_MENU("Ожидание меню"),
    CLICKING_MENU("Клик в меню"),
    WAITING_CONFIRMATION("Ожидание подтверждения"),
    MOVING_TO_NEXT("Переход к следующему");
    
    override fun toString(): String = displayName
}

/**
 * Конечный автомат для управления состояниями бота
 */
class StateMachine {
    var currentState: BotState = BotState.IDLE
        private set
    
    var targetChunk: ChunkPos? = null
    var stateStartTime: Long = 0
        private set
    
    // Данные состояния для передачи между тиками
    val stateData: MutableMap<String, Any> = mutableMapOf()
    
    // Счётчики для отладки
    var retryCount: Int = 0
        private set
    
    /**
     * Переход в новое состояние
     */
    fun transition(newState: BotState) {
        if (currentState != newState) {
            AquamixDrawBot.LOGGER.debug("State: $currentState -> $newState")
            currentState = newState
            stateStartTime = System.currentTimeMillis()
            stateData.clear()
            
            // Сброс счётчика ретраев при успешном переходе вперёд
            if (newState.ordinal > currentState.ordinal) {
                retryCount = 0
            }
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
     * Увеличить счётчик ретраев
     */
    fun incrementRetry(): Int {
        retryCount++
        return retryCount
    }
    
    /**
     * Полный сброс машины состояний
     */
    fun reset() {
        currentState = BotState.IDLE
        targetChunk = null
        stateData.clear()
        retryCount = 0
        stateStartTime = System.currentTimeMillis()
    }
    
    /**
     * Проверка, находимся ли в процессе работы
     */
    fun isWorking(): Boolean = currentState != BotState.IDLE
    
    /**
     * Установить флаг в данных состояния
     */
    fun setFlag(key: String, value: Boolean = true) {
        stateData[key] = value
    }
    
    /**
     * Получить флаг из данных состояния
     */
    fun getFlag(key: String): Boolean = stateData[key] as? Boolean ?: false
}
