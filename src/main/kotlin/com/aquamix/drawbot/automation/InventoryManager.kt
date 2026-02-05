package com.aquamix.drawbot.automation

import com.aquamix.drawbot.AquamixDrawBot
import net.minecraft.block.Blocks
import net.minecraft.client.MinecraftClient
import net.minecraft.item.Items
import net.minecraft.text.Text

/**
 * Менеджер инвентаря
 * Следит за наличием блоков и инструментов
 */
class InventoryManager {
    
    // Debounce for slot switching to prevent spam
    private var lastSlotSwitchTime = 0L
    private val slotSwitchCooldown = 200L // ms
    
    /**
     * Проверить и подготовить инвентарь к работе
     * @return true если всё готово, false если нет ресурсов
     */
    fun checkAndEquip(client: MinecraftClient): Boolean {
        val player = client.player ?: return false
        
        // 1. Проверяем наличие End Portal Frame (Рамка портала в Энд)
        // В Minecraft 1.21 это item: END_PORTAL_FRAME
        val requiredItem = Items.END_PORTAL_FRAME
        
        // Проверяем main hand
        if (player.mainHandStack.isOf(requiredItem)) {
            return true
        }
        
        // Ищем в инвентаре
        for (i in 0 until player.inventory.main.size) {
            val stack = player.inventory.main[i]
            if (stack.isOf(requiredItem)) {
                // Если нашли - переключаемся на этот слот (если он в хотбаре)
                if (i < 9) {
                    // Already on correct slot?
                    if (player.inventory.selectedSlot == i) return true
                    
                    // Debounce check
                    val now = System.currentTimeMillis()
                    if (now - lastSlotSwitchTime < slotSwitchCooldown) return false
                    
                    player.inventory.selectedSlot = i
                    lastSlotSwitchTime = now
                    return true
                } else {
                    // TODO: Если в инвентаре но не в хотбаре - можно поменять местами
                    // Пока просто уведомляем
                    client.player?.sendMessage(Text.literal("§e[DrawBot] Блок в инвентаре, но не в хотбаре!"), false)
                    return false // Пока требуем чтобы был в хотбаре
                }
            }
        }
        
        // Если не нашли
        client.player?.sendMessage(Text.literal("§c[DrawBot] Ошибка: Нет рамок портала (End Portal Frame)!"), false)
        return false
    }
    
    /**
     * Количество блоков в инвентаре
     */
    fun getBlockCount(client: MinecraftClient): Int {
        val player = client.player ?: return 0
        var count = 0
        val requiredItem = Items.END_PORTAL_FRAME
        
        for (stack in player.inventory.main) {
            if (stack.isOf(requiredItem)) {
                count += stack.count
            }
        }
        return count
    }
    /**
     * Экипировать лучшую кирку (Незеритовую или Алмазную)
     * @return true если кир��а экипирована
     */
    fun equipBestPickaxe(client: MinecraftClient): Boolean {
        val player = client.player ?: return false
        
        // Порядок приоритета
        val pickaxes = listOf(
            Items.NETHERITE_PICKAXE,
            Items.DIAMOND_PICKAXE,
            Items.IRON_PICKAXE,
            Items.GOLDEN_PICKAXE,
            Items.STONE_PICKAXE,
            Items.WOODEN_PICKAXE
        )
        
        // 1. Проверяем текущий предмет
        if (player.mainHandStack.item in pickaxes) {
            return true
        }
        
        // 2. Ищем в хотбаре
        for (item in pickaxes) {
            for (i in 0..8) {
                if (player.inventory.getStack(i).isOf(item)) {
                    // Already on correct slot?
                    if (player.inventory.selectedSlot == i) return true
                    
                    // Debounce check
                    val now = System.currentTimeMillis()
                    if (now - lastSlotSwitchTime < slotSwitchCooldown) return false
                    
                    player.inventory.selectedSlot = i
                    lastSlotSwitchTime = now
                    return true
                }
            }
        }
        
        return false
    }
}
