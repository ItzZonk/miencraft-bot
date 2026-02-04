package com.aquamix.drawbot.gui

import com.aquamix.drawbot.schematic.Schematic
import com.aquamix.drawbot.schematic.SchematicManager
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text

/**
 * Экран списка сохранённых схематик
 */
class SchematicListScreen(private val parentScreen: ChunkMapScreen) : Screen(Text.literal("Схематики")) {
    
    private var schematics: List<Schematic> = emptyList()
    private var scrollOffset = 0
    private val itemHeight = 50
    private val listTop = 50
    private val listBottom get() = height - 50
    private val visibleItems get() = (listBottom - listTop) / itemHeight
    
    private var selectedIndex = -1
    
    override fun init() {
        super.init()
        
        // Загружаем список схематик
        schematics = SchematicManager.list()
        
        // Кнопка назад
        addDrawableChild(ButtonWidget.builder(Text.literal("← Назад")) { close() }
            .dimensions(10, 10, 80, 20).build())
        
        // Кнопка обновить
        addDrawableChild(ButtonWidget.builder(Text.literal("↻ Обновить")) { 
            schematics = SchematicManager.list()
            selectedIndex = -1
        }.dimensions(100, 10, 80, 20).build())
        
        // Кнопка загрузить
        addDrawableChild(ButtonWidget.builder(Text.literal("Загрузить")) { loadSelected() }
            .dimensions(width / 2 - 105, height - 35, 100, 25).build())
        
        // Кнопка удалить
        addDrawableChild(ButtonWidget.builder(Text.literal("Удалить")) { deleteSelected() }
            .dimensions(width / 2 + 5, height - 35, 100, 25).build())
    }
    
    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // Фон
        context.fill(0, 0, width, height, 0xFF1a1a2e.toInt())
        
        // Заголовок
        context.drawCenteredTextWithShadow(
            textRenderer, 
            "Сохранённые схематики (${schematics.size})", 
            width / 2, 
            20, 
            0xFFFFFF
        )
        
        // Рамка списка
        context.fill(20, listTop - 2, width - 20, listBottom + 2, 0xFF2d2d44.toInt())
        context.fill(22, listTop, width - 22, listBottom, 0xFF1a1a2e.toInt())
        
        // Элементы списка
        val startIndex = scrollOffset
        val endIndex = minOf(startIndex + visibleItems + 1, schematics.size)
        
        for (i in startIndex until endIndex) {
            val schematic = schematics[i]
            val y = listTop + (i - startIndex) * itemHeight
            
            if (y + itemHeight > listBottom) break
            
            val isSelected = i == selectedIndex
            val isHovered = mouseX in 22..(width - 22) && mouseY in y..(y + itemHeight - 2)
            
            // Фон элемента
            val bgColor = when {
                isSelected -> 0xFF3d5a80.toInt()
                isHovered -> 0xFF2d3d5c.toInt()
                else -> 0xFF252540.toInt()
            }
            context.fill(24, y + 2, width - 24, y + itemHeight - 2, bgColor)
            
            // Название
            context.drawText(textRenderer, "§f${schematic.name}", 30, y + 8, 0xFFFFFF, true)
            
            // Информация
            val info = "§7${schematic.chunkCount} чанков | ID: ${schematic.id}"
            context.drawText(textRenderer, info, 30, y + 22, 0xAAAAAA, true)
            
            // Дата создания
            val date = schematic.createdAt.take(16).replace("T", " ")
            context.drawText(textRenderer, "§8$date", 30, y + 34, 0x888888, true)
        }
        
        // Скроллбар если нужен
        if (schematics.size > visibleItems) {
            val scrollbarHeight = ((visibleItems.toFloat() / schematics.size) * (listBottom - listTop)).toInt()
            val scrollbarY = listTop + ((scrollOffset.toFloat() / (schematics.size - visibleItems)) * (listBottom - listTop - scrollbarHeight)).toInt()
            
            context.fill(width - 28, listTop, width - 24, listBottom, 0xFF333333.toInt())
            context.fill(width - 27, scrollbarY, width - 25, scrollbarY + scrollbarHeight, 0xFF666666.toInt())
        }
        
        // Если список пуст
        if (schematics.isEmpty()) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                "§7Нет сохранённых схематик",
                width / 2,
                listTop + 40,
                0x888888
            )
            context.drawCenteredTextWithShadow(
                textRenderer,
                "§8Создай схематику на карте и нажми 'Сохранить'",
                width / 2,
                listTop + 60,
                0x666666
            )
        }
        
        super.render(context, mouseX, mouseY, delta)
    }
    
    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (super.mouseClicked(mouseX, mouseY, button)) return true
        
        // Клик по списку
        if (mouseX in 22.0..(width - 22.0) && mouseY in listTop.toDouble()..listBottom.toDouble()) {
            val clickedIndex = scrollOffset + ((mouseY - listTop) / itemHeight).toInt()
            if (clickedIndex in schematics.indices) {
                if (selectedIndex == clickedIndex && button == 0) {
                    // Двойной клик - загрузить
                    loadSelected()
                } else {
                    selectedIndex = clickedIndex
                }
                return true
            }
        }
        
        return false
    }
    
    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (schematics.size > visibleItems) {
            scrollOffset = (scrollOffset - verticalAmount.toInt()).coerceIn(0, schematics.size - visibleItems)
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }
    
    private fun loadSelected() {
        if (selectedIndex in schematics.indices) {
            val schematic = schematics[selectedIndex]
            parentScreen.loadSchematic(schematic)
            client?.setScreen(parentScreen)
        }
    }
    
    private fun deleteSelected() {
        if (selectedIndex in schematics.indices) {
            val schematic = schematics[selectedIndex]
            if (SchematicManager.delete(schematic.id)) {
                schematics = SchematicManager.list()
                selectedIndex = -1
            }
        }
    }
    
    override fun close() {
        client?.setScreen(parentScreen)
    }
    
    override fun shouldPause() = false
}
