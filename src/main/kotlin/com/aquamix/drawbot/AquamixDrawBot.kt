package com.aquamix.drawbot

import com.aquamix.drawbot.automation.BotController
import com.aquamix.drawbot.config.ModConfig
import com.aquamix.drawbot.data.ProgressTracker
import com.aquamix.drawbot.gui.ChunkMapScreen
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory
import com.aquamix.drawbot.render.HudOverlay
import com.aquamix.drawbot.render.WorldRenderer
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents

object AquamixDrawBot : ClientModInitializer {
    const val MOD_ID = "aquamix-draw-bot"
    val LOGGER = LoggerFactory.getLogger(MOD_ID)
    
    // Keybindings
    private lateinit var openMapKey: KeyBinding
    private lateinit var toggleBotKey: KeyBinding
    
    // Controllers
    lateinit var botController: BotController
        private set
    
    lateinit var progressTracker: ProgressTracker
        private set
    
    override fun onInitializeClient() {
        LOGGER.info("Initializing Aquamix Draw Bot for mc.aqua-mix.com")
        
        // Load config
        ModConfig.load()
        
        // Initialize progress tracker
        progressTracker = ProgressTracker()
        progressTracker.load()
        
        // Register keybindings
        registerKeybindings()
        
        // Initialize bot controller
        botController = BotController()
        
        // Register tick event for bot logic
        // Use START_CLIENT_TICK so our input overrides (key presses) are applied 
        // AFTER hardware polling but BEFORE player physics tick.
        ClientTickEvents.START_CLIENT_TICK.register { client ->
            handleKeyBindings(client)
            if (client.player != null && client.world != null) {
                // FORCE INPUT SIMULATION
                // This replaces the Mixin approach. We manually press the keys requested by the bot.
                // Doing this in START_CLIENT_TICK ensures it overrides hardware input for this frame.
                applyBotInputOverrides(client)
                
                botController.tick(client)
            }
        }
        
        // Register chat message listener for confirmation detection
        ClientReceiveMessageEvents.GAME.register { message, _ ->
            val text = message.string
            botController.onChatMessage(text)
        }
        
        // Регистрация рендера
        HudRenderCallback.EVENT.register(HudOverlay)
        WorldRenderEvents.AFTER_TRANSLUCENT.register(WorldRenderer)
        
        LOGGER.info("Aquamix Draw Bot initialized successfully!")
        LOGGER.info("Press M to open chunk map, B to toggle bot")
    }
    
    private fun registerKeybindings() {
        openMapKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.aquamix.open_map",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                "category.aquamix.drawbot"
            )
        )
        
        toggleBotKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.aquamix.toggle_bot",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                "category.aquamix.drawbot"
            )
        )
    }
    
    private fun handleKeyBindings(client: MinecraftClient) {
        // Only handle when in-game and no screen is open (except our map)
        if (client.player == null) return
        
        while (openMapKey.wasPressed()) {
            if (client.currentScreen == null || client.currentScreen is ChunkMapScreen) {
                client.setScreen(ChunkMapScreen())
            }
        }
        
        while (toggleBotKey.wasPressed()) {
            if (client.currentScreen == null) {
                botController.toggle()
            }
        }
    }
    
    /**
     * Applies the key press states from InputOverrideHandler to the vanilla KeyBindings.
     * This simulates physical key presses.
     */
    private fun applyBotInputOverrides(client: MinecraftClient) {
        val options = client.options
        if (com.aquamix.drawbot.input.InputOverrideHandler.isInControl()) {
            // Helper to force a key pressed
            fun forceKey(key: KeyBinding, input: com.aquamix.drawbot.input.BotInput) {
                val pressed = com.aquamix.drawbot.input.InputOverrideHandler.isInputForced(input)
                
                // Method 1: Set the boolean flag (used by high-level logic)
                key.isPressed = pressed
                
                // Method 2: Set the raw input state (used by low-level logic/Litematica/etc)
                // We only set it to true to avoid blocking real input if we are mixing (though we usually take full control)
                if (pressed) {
                    KeyBinding.setKeyPressed(InputUtil.fromTranslationKey(key.boundKeyTranslationKey), true)
                }
            }
            
            forceKey(options.forwardKey, com.aquamix.drawbot.input.BotInput.MOVE_FORWARD)
            forceKey(options.backKey, com.aquamix.drawbot.input.BotInput.MOVE_BACK)
            forceKey(options.leftKey, com.aquamix.drawbot.input.BotInput.MOVE_LEFT)
            forceKey(options.rightKey, com.aquamix.drawbot.input.BotInput.MOVE_RIGHT)
            forceKey(options.jumpKey, com.aquamix.drawbot.input.BotInput.JUMP)
            forceKey(options.sneakKey, com.aquamix.drawbot.input.BotInput.SNEAK)
            forceKey(options.sprintKey, com.aquamix.drawbot.input.BotInput.SPRINT)
            
            // Mouse clicks are handled differently (usually interaction manager), 
            // but for simple hold-actions we can simulate them too if needed.
        }
    }
}
