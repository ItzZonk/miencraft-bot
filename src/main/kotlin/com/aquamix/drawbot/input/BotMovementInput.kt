package com.aquamix.drawbot.input

import com.aquamix.drawbot.AquamixDrawBot
import net.minecraft.client.input.Input

/**
 * Baritone-style MovementInput replacement
 * Полностью контролирует движение когда бот активен
 * 
 * ВАЖНО: Этот класс НЕ наследует от KeyboardInput!
 * Он напрямую наследует от Input и полностью контролирует все поля.
 */
class BotMovementInput : Input() {
    
    init {
        AquamixDrawBot.LOGGER.info("[BotMovementInput] Created new BotMovementInput instance")
    }
    
    /**
     * Вызывается Minecraft'ом каждый тик для обновления состояния движения
     * Эквивалент Baritone's updatePlayerMoveState()
     */
    override fun tick(slowDown: Boolean, f: Float) {
        // Сбрасываем всё
        this.movementForward = 0.0f
        this.movementSideways = 0.0f
        
        // Применяем состояние из InputOverrideHandler (как делает Baritone)
        this.jumping = InputOverrideHandler.isInputForced(BotInput.JUMP)
        
        if (InputOverrideHandler.isInputForced(BotInput.MOVE_FORWARD)) {
            this.pressingForward = true
            this.movementForward += 1.0f
        } else {
            this.pressingForward = false
        }
        
        if (InputOverrideHandler.isInputForced(BotInput.MOVE_BACK)) {
            this.pressingBack = true
            this.movementForward -= 1.0f
        } else {
            this.pressingBack = false
        }
        
        if (InputOverrideHandler.isInputForced(BotInput.MOVE_LEFT)) {
            this.pressingLeft = true
            this.movementSideways += 1.0f
        } else {
            this.pressingLeft = false
        }
        
        if (InputOverrideHandler.isInputForced(BotInput.MOVE_RIGHT)) {
            this.pressingRight = true
            this.movementSideways -= 1.0f
        } else {
            this.pressingRight = false
        }
        
        this.sneaking = InputOverrideHandler.isInputForced(BotInput.SNEAK)
        
        // Применяем slowDown модификатор (как Baritone делает для sneak)
        if (this.sneaking) {
            this.movementForward *= 0.3f
            this.movementSideways *= 0.3f
        }
        
        // Debug
        if (this.movementForward != 0.0f || this.jumping) {
            AquamixDrawBot.LOGGER.info("[BotMovementInput] tick: fwd=${this.movementForward}, jump=${this.jumping}")
        }
    }
}
