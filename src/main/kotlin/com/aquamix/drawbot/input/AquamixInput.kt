package com.aquamix.drawbot.input

import net.minecraft.client.input.KeyboardInput
import net.minecraft.client.option.GameOptions

/**
 * Custom Input implementation that reads movement overrides from InputOverrideHandler.
 * Based on Baritone's PlayerMovementInput pattern.
 * 
 * When InputOverrideHandler has forced inputs, this class applies them.
 * Otherwise, it delegates to vanilla KeyboardInput behavior.
 */
class AquamixInput(settings: GameOptions) : KeyboardInput(settings) {
    
    override fun tick(slowDown: Boolean, f: Float) {
        // First, let vanilla KeyboardInput process real keyboard state
        super.tick(slowDown, f)
        
        // Then, apply overrides from InputOverrideHandler if bot is in control
        if (InputOverrideHandler.isInControl()) {
            applyBotOverrides()
            
            // Recalculate movement vectors with overridden values
            this.movementForward = calculateMovement(pressingForward, pressingBack)
            this.movementSideways = calculateMovement(pressingLeft, pressingRight)
            
            if (slowDown) {
                this.movementSideways *= 0.3f
                this.movementForward *= 0.3f
            }
        }
    }
    
    /**
     * Apply forced inputs from the central handler.
     */
    private fun applyBotOverrides() {
        // Movement
        if (InputOverrideHandler.isInputForced(BotInput.MOVE_FORWARD)) {
            this.pressingForward = true
        }
        if (InputOverrideHandler.isInputForced(BotInput.MOVE_BACK)) {
            this.pressingBack = true
        }
        if (InputOverrideHandler.isInputForced(BotInput.MOVE_LEFT)) {
            this.pressingLeft = true
        }
        if (InputOverrideHandler.isInputForced(BotInput.MOVE_RIGHT)) {
            this.pressingRight = true
        }
        
        // Actions
        if (InputOverrideHandler.isInputForced(BotInput.JUMP)) {
            this.jumping = true
        }
        if (InputOverrideHandler.isInputForced(BotInput.SNEAK)) {
            this.sneaking = true
        }
    }
    
    /**
     * Calculate movement value from two opposing direction flags.
     */
    private fun calculateMovement(positive: Boolean, negative: Boolean): Float {
        return when {
            positive == negative -> 0.0f
            positive -> 1.0f
            else -> -1.0f
        }
    }
}

