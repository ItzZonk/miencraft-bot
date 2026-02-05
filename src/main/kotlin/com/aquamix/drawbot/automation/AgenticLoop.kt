package com.aquamix.drawbot.automation

import com.aquamix.drawbot.AquamixDrawBot

/**
 * Агентский цикл самокоррекции (Agentic Loop)
 * 
 * Основано на исследовании: цикл "Мышление — Действие — Наблюдение"
 * Бот анализирует ошибку, пытается исправить, наблюдает результат.
 * Система способна выдерживать до MAX_RETRIES итераций самоисправления.
 */
object AgenticLoop {
    
    /** Максимальное количество попыток самокоррекции */
    const val MAX_RETRIES = 10
    
    /**
     * Обработать ошибку и вернуть новое состояние для восстановления
     * 
     * @param error Ошибка, которую нужно обработать
     * @param currentState Текущее состояние бота
     * @param healingAttempt Номер текущей попытки (1-based)
     * @return Новое состояние для перехода (или Idle если исчерпаны попытки)
     */
    fun handleError(
        error: BotError,
        currentState: BotState,
        healingAttempt: Int = 1
    ): BotState {
        AquamixDrawBot.LOGGER.warn(
            "[AgenticLoop] Error: ${error.message}, attempt $healingAttempt/$MAX_RETRIES"
        )
        
        // Если исчерпаны попытки - сдаёмся
        if (healingAttempt >= MAX_RETRIES) {
            AquamixDrawBot.LOGGER.error(
                "[AgenticLoop] Max retries reached, giving up on current chunk"
            )
            return BotState.Idle
        }
        
        // Определяем стратегию восстановления в зависимости от типа ошибки
        return when (error) {
            is BotError.Timeout -> handleTimeout(error, currentState, healingAttempt)
            is BotError.MenuNotFound -> handleMenuNotFound(currentState, healingAttempt)
            is BotError.ButtonNotFound -> handleButtonNotFound(currentState, healingAttempt)
            is BotError.FlightFailed -> handleFlightFailed(currentState, healingAttempt)
            is BotError.NoBurInInventory -> BotState.Idle // Критическая ошибка
            is BotError.Stuck -> handleStuck(currentState, healingAttempt)
        }
    }
    
    /**
     * Обработка таймаута - повторяем предыдущее действие
     */
    private fun handleTimeout(
        error: BotError.Timeout,
        currentState: BotState,
        attempt: Int
    ): BotState {
        AquamixDrawBot.LOGGER.debug("[AgenticLoop] Timeout recovery: retry operation")
        
        return when (currentState) {
            is BotState.WaitingForMenu -> {
                // Таймаут ожидания меню -> пробуем снова поставить БУР
                BotState.PlacingBur(currentState.target, attempt)
            }
            is BotState.ClickingMenu -> {
                // Таймаут клика -> повторяем
                BotState.ClickingMenu(currentState.target)
            }
            is BotState.WaitingConfirmation -> {
                // Таймаут подтверждения -> предполагаем успех и двигаемся дальше
                AquamixDrawBot.LOGGER.info("[AgenticLoop] Confirmation timeout, assuming success")
                BotState.MovingToNext(currentState.target)
            }
            is BotState.Landing -> {
                // Таймаут приземления -> пробуем лететь заново
                BotState.FlyingToChunk(currentState.target)
            }
            is BotState.SelfHealing -> {
                // Уже в режиме восстановления - увеличиваем счётчик
                BotState.SelfHealing(error, currentState.previousState, attempt + 1)
            }
            else -> currentState
        }
    }
    
    /**
     * Обработка "меню не найдено" - повторяем установку БУРа
     */
    private fun handleMenuNotFound(
        currentState: BotState,
        attempt: Int
    ): BotState {
        AquamixDrawBot.LOGGER.debug("[AgenticLoop] Menu not found, retrying BUR placement")
        
        val target = extractTarget(currentState) ?: return BotState.Idle
        return BotState.PlacingBur(target, attempt)
    }
    
    /**
     * Обработка "кнопка не найдена" - закрываем меню и пробуем снова
     */
    private fun handleButtonNotFound(
        currentState: BotState,
        attempt: Int
    ): BotState {
        AquamixDrawBot.LOGGER.debug("[AgenticLoop] Button not found, reopening menu")
        
        val target = extractTarget(currentState) ?: return BotState.Idle
        return BotState.PlacingBur(target, attempt)
    }
    
    /**
     * Обработка ошибки полёта - пробуем снова
     */
    private fun handleFlightFailed(
        currentState: BotState,
        attempt: Int
    ): BotState {
        AquamixDrawBot.LOGGER.debug("[AgenticLoop] Flight failed, retrying")
        
        val target = extractTarget(currentState) ?: return BotState.Idle
        return BotState.FlyingToChunk(target)
    }
    
    /**
     * Обработка застревания - отлетаем и пробуем снова
     */
    private fun handleStuck(
        currentState: BotState,
        attempt: Int
    ): BotState {
        AquamixDrawBot.LOGGER.debug("[AgenticLoop] Stuck detected, flying away and retrying")
        
        val target = extractTarget(currentState) ?: return BotState.Idle
        return BotState.FlyingToChunk(target)
    }
    
    /**
     * Извлечь целевой чанк из состояния
     */
    private fun extractTarget(state: BotState): ChunkPos? = when (state) {
        is BotState.FlyingToChunk -> state.target
        is BotState.FlyingToBlock -> state.targetChunk
        is BotState.Landing -> state.target
        is BotState.Landing -> state.target
        is BotState.PlacingBur -> state.target
        is BotState.WaitingForMenu -> state.target
        is BotState.ClickingMenu -> state.target
        is BotState.WaitingConfirmation -> state.target
        is BotState.MovingToNext -> state.completedChunk
        is BotState.Ascending -> state.fromChunk
        is BotState.SelfHealing -> extractTarget(state.previousState)
        is BotState.Idle -> null
    }
    
    /**
     * Определить, следует ли пропустить текущий чанк
     * (после слишком многих неудачных попыток на одном этапе)
     */
    fun shouldSkipChunk(state: BotState): Boolean {
        return when (state) {
            is BotState.PlacingBur -> state.retryCount >= 5
            is BotState.SelfHealing -> state.healingAttempt >= MAX_RETRIES
            else -> false
        }
    }
}
