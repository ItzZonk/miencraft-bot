package com.aquamix.drawbot.automation

import com.aquamix.drawbot.AquamixDrawBot
import com.aquamix.drawbot.config.ModConfig
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.item.Items
import net.minecraft.screen.slot.SlotActionType
import net.minecraft.util.Hand
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.hit.HitResult

/**
 * Обработчик взаимодействия с БУРом и его меню
 */
class ChunkBreaker {
    private var lastActionTime = 0L
    
    /**
     * Находит БУР в хотбаре и использует его (ПКМ)
     * @return true если действие выполнено
     */
    /**
     * Finds BUR in hotbar and places it on the block below
     * Matches user request: looks down and uses raycast
     */
    fun placeBur(client: MinecraftClient): Boolean {
        val player = client.player ?: return false
        val interactionManager = client.interactionManager ?: return false
        
        if (!canAct()) return false
        
        // 1. Look DOWN to place block reliably
        // 1. Look at the target block (Forward-Down)
        // Since we land 2 blocks away, pitch ~45-60 should aim at the block in front of us at feet level
        val targetPitch = 50f
        
        if (kotlin.math.abs(player.pitch - targetPitch) > 5f) {
            player.pitch = targetPitch
            // Look towards the center of the chunk? 
            // Validating rotation takes time, return false to wait
            markAction()
            return false 
        }
        
        // 2. Find BUR in hotbar
        val inventory = player.inventory
        var burSlot = -1
        
        for (i in 0..8) {
            val stack = inventory.getStack(i)
            if (stack.item == Items.END_PORTAL_FRAME) {
                burSlot = i
                break
            }
        }
        
        if (burSlot == -1) {
            AquamixDrawBot.LOGGER.warn("BUR (End Portal Frame) not found in hotbar!")
            return false
        }
        
        // Select slot
        if (inventory.selectedSlot != burSlot) {
            inventory.selectedSlot = burSlot
            markAction()
            return false
        }
        
        // 3. Place using Crosshair Target (Raycast)
        // This fixes "manual placement" issue by interacting with the specific block
        val hit = client.crosshairTarget
        if (hit is BlockHitResult && hit.type == HitResult.Type.BLOCK) {
            interactionManager.interactBlock(player, Hand.MAIN_HAND, hit)
            markAction()
            AquamixDrawBot.LOGGER.debug("BUR placed via raycast interaction")
            return true
        }
        
        AquamixDrawBot.LOGGER.debug("Cannot place BUR: no block target found (Raycast fail)")
        // Optional: Notify user why it failed if in debug mode or repeatedly
        if (AquamixDrawBot.LOGGER.isDebugEnabled) {
            client.player?.sendMessage(net.minecraft.text.Text.of("§7[Debug] Raycast MISS. Pitch: ${player.pitch}"), true)
        }
        return false
    }
    
    /**
     * Проверяет, открыто ли меню БУРа
     */
    fun isBurMenuOpen(client: MinecraftClient): Boolean {
        val screen = client.currentScreen ?: return false
        
        if (screen !is HandledScreen<*>) return false
        
        val title = screen.title.string
        val config = ModConfig.data.bur
        
        return title.contains(config.menuTitle, ignoreCase = true)
    }
    
    /**
     * Кликает по кнопке "Сломать все уровни" (незеритовая кирка)
     * @return true если клик выполнен успешно
     */
    fun clickBreakAll(client: MinecraftClient): Boolean {
        val screen = client.currentScreen
        
        if (screen !is GenericContainerScreen) {
            AquamixDrawBot.LOGGER.debug("Не контейнерное окно: ${screen?.javaClass?.simpleName}")
            return false
        }
        
        if (!canAct()) return false
        
        val handler = screen.screenHandler
        val config = ModConfig.data.bur
        val pattern = Regex(config.breakButtonPattern, RegexOption.IGNORE_CASE)
        
        // Ищем нужный слот в меню
        // По скриншоту: слот 1 (второй) - незеритовая кирка
        for (slot in handler.slots) {
            if (slot.inventory == client.player?.inventory) continue // Пропускаем инвентарь игрока
            
            val stack = slot.stack
            if (stack.isEmpty) continue
            
            // Проверяем название предмета
            val displayName = stack.name.string
            val itemId = stack.item.toString()
            
            AquamixDrawBot.LOGGER.debug("Слот ${slot.id}: $displayName ($itemId)")
            
            // Ищем незеритовую кирку или текст "Сломать все"
            if (itemId.contains("netherite_pickaxe") || 
                pattern.containsMatchIn(displayName)) {
                
                AquamixDrawBot.LOGGER.info("Найдена кнопка в слоте ${slot.id}: $displayName")
                
                // Кликаем по слоту
                client.interactionManager?.clickSlot(
                    handler.syncId,
                    slot.id,
                    0, // Left click
                    SlotActionType.PICKUP,
                    client.player
                )
                
                markAction()
                
                // Закрываем меню после небольшой задержки
                // Обрабатывается в BotController после перехода состояния
                
                return true
            }
        }
        
        AquamixDrawBot.LOGGER.warn("Кнопка 'Сломать все уровни' не найдена в меню!")
        
        // Пробуем кликнуть по слоту 1 напрямую (как на скриншоте)
        if (handler.slots.size > 1) {
            val fallbackSlot = handler.slots[1]
            if (!fallbackSlot.stack.isEmpty) {
                AquamixDrawBot.LOGGER.info("Пробуем слот 1 напрямую: ${fallbackSlot.stack.name.string}")
                client.interactionManager?.clickSlot(
                    handler.syncId,
                    1,
                    0,
                    SlotActionType.PICKUP,
                    client.player
                )
                markAction()
                return true
            }
        }
        
        return false
    }
    
    /**
     * Закрывает текущее меню
     */
    fun closeMenu(client: MinecraftClient) {
        client.player?.closeHandledScreen()
    }
    
    /**
     * Проверка возможности выполнить действие (cooldown)
     */
    private fun canAct(): Boolean {
        val delay = ModConfig.data.timing.actionCooldown
        return System.currentTimeMillis() - lastActionTime > delay
    }
    
    /**
     * Отметка времени последнего действия
     */
    private fun markAction() {
        lastActionTime = System.currentTimeMillis()
    }
}
