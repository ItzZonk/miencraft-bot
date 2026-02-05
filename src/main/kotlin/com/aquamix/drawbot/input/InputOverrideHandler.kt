package com.aquamix.drawbot.input

import com.aquamix.drawbot.AquamixDrawBot
import net.minecraft.client.MinecraftClient
import net.minecraft.client.input.Input
import net.minecraft.client.input.KeyboardInput

/**
 * Baritone-style InputOverrideHandler
 * 
 * КЛЮЧЕВАЯ ЛОГИКА: В onTick() заменяем player.input на BotMovementInput когда бот активен,
 * и восстанавливаем KeyboardInput когда бот неактивен.
 * Это ТОЧНО как делает Baritone!
 */
object InputOverrideHandler {
    
    private val forcedInputs = mutableMapOf<BotInput, Boolean>()
    
    // Сохраняем оригинальный Input для восстановления
    private var savedOriginalInput: Input? = null
    
    fun isInputForced(input: BotInput): Boolean = forcedInputs[input] ?: false
    
    fun setInputForced(input: BotInput, forced: Boolean) {
        forcedInputs[input] = forced
    }
    
    fun clearAll() {
        forcedInputs.clear()
    }
    
    /**
     * Проверяет, контролирует ли бот игрока (как Baritone inControl())
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
     * КЛЮЧЕВОЙ МЕТОД - вызывается каждый тик!
     * Заменяет player.input на BotMovementInput когда бот активен
     * Это ТОЧНО как делает Baritone в onTick()
     */
    fun onTick(client: MinecraftClient) {
        val player = client.player ?: return
        
        if (isInControl()) {
            // Бот активен - нужен BotMovementInput
            if (player.input !is BotMovementInput) {
                // Сохраняем оригинальный Input
                savedOriginalInput = player.input
                // Заменяем на наш
                player.input = BotMovementInput()
                AquamixDrawBot.LOGGER.info("[InputOverrideHandler] REPLACED player.input with BotMovementInput!")
            }
        } else {
            // Бот НЕ активен - восстанавливаем оригинальный Input
            if (player.input is BotMovementInput) {
                // Восстанавливаем сохранённый или создаём новый KeyboardInput
                player.input = savedOriginalInput ?: KeyboardInput(client.options)
                AquamixDrawBot.LOGGER.info("[InputOverrideHandler] RESTORED original KeyboardInput!")
                savedOriginalInput = null
            }
        }
    }
    
    /**
     * Полный сброс при остановке бота
     */
    fun reset(client: MinecraftClient) {
        clearAll()
        val player = client.player ?: return
        
        if (player.input is BotMovementInput) {
            player.input = savedOriginalInput ?: KeyboardInput(client.options)
            AquamixDrawBot.LOGGER.info("[InputOverrideHandler] RESET: restored KeyboardInput")
        }
        savedOriginalInput = null
    }
    
    fun getDebugState(): String {
        return forcedInputs.filter { it.value }.keys.joinToString(", ") { it.name }
    }
}
