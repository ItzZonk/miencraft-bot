package com.aquamix.drawbot.features

import com.aquamix.drawbot.AquamixDrawBot
import com.aquamix.drawbot.anticheat.HumanSimulator
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen
import net.minecraft.item.Items
import net.minecraft.screen.slot.SlotActionType
import net.minecraft.text.Text

object CaptchaSolver {
    private val TITLE_PATTERN = Regex("Нажмите на цифру [\"']?(\\d+)[\"']?", RegexOption.IGNORE_CASE)
    private var lastSolvedTime = 0L

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            solveCaptcha(client)
        }
    }

    private fun solveCaptcha(client: MinecraftClient) {
        val screen = client.currentScreen
        if (screen !is GenericContainerScreen) return
        
        // Check cooldown to prevent spam clicking
        if (System.currentTimeMillis() - lastSolvedTime < 1000) return

        val title = screen.title.string
        val match = TITLE_PATTERN.find(title) ?: return

        val requiredDigit = match.groupValues[1]

        val handler = screen.screenHandler
        val interactionManager = client.interactionManager ?: return
        val player = client.player ?: return

        // Scan slots
        // Items are in top inventory usually (chest)
        // handler.inventory is the combined inventory, need to check slots directly
        
        for (slot in handler.slots) {
            // Optimization: Only check chest slots, not player inventory (usually top inventory)
            // But checking all is safer and fast enough.
            
            if (!slot.hasStack()) continue
            
            val stack = slot.stack
            if (stack.item == Items.PAPER) {
                val name = stack.name.string
                
                // User said "name of the number". E.g. "6" or "Digit 6"
                // We check if the name CONTAINS the digit strictly? 
                // "1" vs "12"? The numbers seem single digit in example.
                // Assuming precise match or word boundary is safer, but strict contains is good start.
                // Screenshot showed tooltip "1". So name likely just "1".
                
                if (name.contains(requiredDigit)) {
                    AquamixDrawBot.LOGGER.info("[CaptchaSolver] Found digit $requiredDigit in slot ${slot.id} ($name). Clicking...")
                    
                    // Click
                    interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.PICKUP, player)
                    
                    lastSolvedTime = System.currentTimeMillis()
                    
                    // Notify user
                    player.sendMessage(Text.literal("§a[DrawBot] Капча $requiredDigit пройдена!"), true)
                    return
                }
            }
        }
    }
}
