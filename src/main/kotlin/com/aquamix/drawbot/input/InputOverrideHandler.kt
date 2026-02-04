package com.aquamix.drawbot.input

/**
 * Centralized handler for bot input overrides.
 * Based on Baritone's InputOverrideHandler pattern.
 * 
 * Usage:
 *   InputOverrideHandler.setInputForced(BotInput.MOVE_FORWARD, true)
 *   InputOverrideHandler.clearAll()
 */
object InputOverrideHandler {
    
    private val forcedInputs = mutableMapOf<BotInput, Boolean>()
    
    /**
     * Check if a specific input is currently forced by the bot.
     */
    fun isInputForced(input: BotInput): Boolean = forcedInputs[input] ?: false
    
    /**
     * Set the forced state of an input.
     * @param input The input to control
     * @param forced True to force the input down, false to release
     */
    fun setInputForced(input: BotInput, forced: Boolean) {
        forcedInputs[input] = forced
    }
    
    /**
     * Clear all forced inputs. Call this when stopping the bot.
     */
    fun clearAll() {
        forcedInputs.clear()
    }
    
    /**
     * Returns true if the bot is currently forcing any movement inputs.
     * Used to determine if we should be "in control" of the player.
     */
    fun isInControl(): Boolean {
        return forcedInputs.any { (input, forced) -> 
            forced && input in listOf(
                BotInput.MOVE_FORWARD, BotInput.MOVE_BACK,
                BotInput.MOVE_LEFT, BotInput.MOVE_RIGHT,
                BotInput.JUMP, BotInput.SNEAK
            )
        }
    }
    
    /**
     * Debug: Get current state as string
     */
    fun getDebugState(): String {
        return forcedInputs.filter { it.value }.keys.joinToString(", ") { it.name }
    }
}
