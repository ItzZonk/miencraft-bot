package com.aquamix.drawbot.input

import com.aquamix.drawbot.AquamixDrawBot
import net.minecraft.client.input.KeyboardInput
import net.minecraft.client.option.GameOptions

/**
 * Baritone-style Input implementation.
 * Заменяет KeyboardInput через mixin и напрямую устанавливает движение.
 */
class AquamixInput(settings: GameOptions) : KeyboardInput(settings) {
    
    init {
        AquamixDrawBot.LOGGER.info("[AquamixInput] === MIXIN РАБОТАЕТ! AquamixInput создан ===")
    }
    
    override fun tick(slowDown: Boolean, f: Float) {
        // Сначала реальный ввод с клавиатуры
        super.tick(slowDown, f)
        
        // Если бот контролирует - перезаписываем значения напрямую
        if (InputOverrideHandler.isInControl()) {
            // Сбрасываем всё что поставил super.tick()
            var forward = 0.0f
            var sideways = 0.0f
            
            // Применяем состояние бота
            if (InputOverrideHandler.isInputForced(BotInput.MOVE_FORWARD)) {
                forward += 1.0f
                this.pressingForward = true
            }
            if (InputOverrideHandler.isInputForced(BotInput.MOVE_BACK)) {
                forward -= 1.0f
                this.pressingBack = true
            }
            if (InputOverrideHandler.isInputForced(BotInput.MOVE_LEFT)) {
                sideways += 1.0f
                this.pressingLeft = true
            }
            if (InputOverrideHandler.isInputForced(BotInput.MOVE_RIGHT)) {
                sideways -= 1.0f
                this.pressingRight = true
            }
            
            // slowDown применяется при приседании
            if (slowDown) {
                forward *= 0.3f
                sideways *= 0.3f
            }
            
            // НАПРЯМУЮ устанавливаем поля движения
            this.movementForward = forward
            this.movementSideways = sideways
            
            // Прыжок и приседание
            if (InputOverrideHandler.isInputForced(BotInput.JUMP)) {
                this.jumping = true
            }
            if (InputOverrideHandler.isInputForced(BotInput.SNEAK)) {
                this.sneaking = true
            }
            
            // Debug log
            AquamixDrawBot.LOGGER.info(
                "[AquamixInput] Applied: fwd=$forward, side=$sideways, jump=${this.jumping}"
            )
        }
    }
}
