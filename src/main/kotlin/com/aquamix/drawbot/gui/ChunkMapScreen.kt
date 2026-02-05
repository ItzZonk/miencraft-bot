package com.aquamix.drawbot.gui

import com.aquamix.drawbot.AquamixDrawBot
import com.aquamix.drawbot.automation.ChunkPos
import com.aquamix.drawbot.schematic.Schematic
import com.aquamix.drawbot.schematic.SchematicManager
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import net.minecraft.world.Heightmap
import net.minecraft.block.MapColor
import kotlin.math.floor

/**
 * Главный экран с интерактивной картой чанков
 */
class ChunkMapScreen : Screen(Text.literal("Chunk Map")) {
    
    enum class MapMode {
        SIMPLE, // Схематичная (сетка)
        TERRAIN // Реалистичная (блоки)
    }

    // Смещение карты (в координатах чанков)
    private var offsetX = 0.0
    private var offsetZ = 0.0
    
    // Масштаб
    private var zoom = 1.0
    
    // Режим карты
    private var mapMode = MapMode.SIMPLE
    
    // Размер чанка на экране в пикселях
    private val baseChunkSize = 24
    private val chunkSize: Int get() = (baseChunkSize * zoom).toInt()
    
    // Выбранные чанки
    private val selectedChunks = mutableSetOf<ChunkPos>()
    
    // Перетаскивание карты
    private var isDragging = false
    private var dragStartX = 0.0
    private var dragStartY = 0.0
    private var dragOffsetX = 0.0
    private var dragOffsetZ = 0.0
    
    // Выделение прямоугольником
    private var isSelecting = false
    private var selectStartChunk: ChunkPos? = null
    
    // Виджеты
    private var schematicNameField: TextFieldWidget? = null
    private var modeButton: ButtonWidget? = null
    
    // Цвета
    private val colorBackground = 0xFF1a1a2e.toInt()
    private val colorGrid = 0xFF2d2d44.toInt()
    private val colorChunkDefault = 0xFF3d3d5c.toInt()
    private val colorChunkSelected = 0xFFff4757.toInt()
    private val colorChunkQueued = 0xFFffa502.toInt()
    private val colorChunkCompleted = 0xFF2ed573.toInt()
    private val colorChunkCurrent = 0xFF1e90ff.toInt()
    private val colorPlayer = 0xFF00d9ff.toInt()
    private val colorText = 0xFFFFFFFF.toInt()
    private val colorTextDim = 0xFFaaaaaa.toInt()
    private val colorVoid = 0xFF000000.toInt()
    
    // Block Brush
    private var brushWidth = 1
    private var brushHeight = 1
    
    // Scanner Timer
    private var scanTickCounter = 0

    
    override fun init() {
        super.init()
        
        // Центрируем на позиции игрока
        client?.player?.let { player ->
            offsetX = (player.blockPos.x shr 4).toDouble()
            offsetZ = (player.blockPos.z shr 4).toDouble()
        }
        
        // Загружаем ранее выбранные чанки из очереди бота
        selectedChunks.addAll(AquamixDrawBot.botController.getQueuedChunks())
        
        val buttonY = 10
        val buttonHeight = 20
        var buttonX = 10
        
        // Кнопка запуска
        addDrawableChild(ButtonWidget.builder(Text.literal("▶ Запустить")) { startBot() }
            .dimensions(buttonX, buttonY, 90, buttonHeight).build())
        buttonX += 95
        
        // Кнопка остановки
        addDrawableChild(ButtonWidget.builder(Text.literal("⏹ Стоп")) { stopBot() }
            .dimensions(buttonX, buttonY, 60, buttonHeight).build())
        buttonX += 65
        
        // Кнопка очистки выбора
        addDrawableChild(ButtonWidget.builder(Text.literal("✕ Очистить")) { clearSelection() }
            .dimensions(buttonX, buttonY, 80, buttonHeight).build())
        buttonX += 85
        
        // Разделитель
        buttonX += 20
        
        // Поле ввода имени схематики
        schematicNameField = TextFieldWidget(textRenderer, buttonX, buttonY, 120, buttonHeight, Text.literal("Название"))
        schematicNameField?.setPlaceholder(Text.literal("Название схематики"))
        addDrawableChild(schematicNameField)
        buttonX += 125
        
        // Кнопка сохранения
        addDrawableChild(ButtonWidget.builder(Text.literal("💾 Сохранить")) { saveSchematic() }
            .dimensions(buttonX, buttonY, 90, buttonHeight).build())
        buttonX += 95
        
        // Кнопка загрузки
        addDrawableChild(ButtonWidget.builder(Text.literal("📂 Загрузить")) { openLoadDialog() }
            .dimensions(buttonX, buttonY, 90, buttonHeight).build())
            
        // Кнопка переключения режима
        val modeText = if (mapMode == MapMode.SIMPLE) "🗺 Схема" else "🌍 Террейн"
        modeButton = ButtonWidget.builder(Text.literal(modeText)) { toggleMapMode() }
            .dimensions(width - 120, 10, 80, 20).build()
        addDrawableChild(modeButton)

        // Кнопки масштаба
        addDrawableChild(ButtonWidget.builder(Text.literal("+")) { zoom = (zoom * 1.2).coerceIn(0.3, 4.0) }
            .dimensions(width - 35, 10, 25, 25).build())
        addDrawableChild(ButtonWidget.builder(Text.literal("-")) { zoom = (zoom / 1.2).coerceIn(0.3, 4.0) }
            .dimensions(width - 35, 40, 25, 25).build())
        
        // Кнопка центрирования на игроке
        addDrawableChild(ButtonWidget.builder(Text.literal("⌖")) { centerOnPlayer() }
            .dimensions(width - 35, 75, 25, 25).build())
            
        // --- Brush Controls ---
        var rightY = 110
        val rightX = width - 80
        
        // Label
        addDrawableChild(ButtonWidget.builder(Text.literal("Кисть: ${brushWidth}x${brushHeight}")) { }
            .dimensions(rightX, rightY, 70, 20).build().apply { active = false })
        
        rightY += 25
        
        // Width
        addDrawableChild(ButtonWidget.builder(Text.literal("W-")) { 
            brushWidth = (brushWidth - 1).coerceAtLeast(1)
            refreshInit()
        }.dimensions(rightX, rightY, 35, 20).build())
        
        addDrawableChild(ButtonWidget.builder(Text.literal("W+")) { 
            brushWidth = (brushWidth + 1).coerceAtMost(32)
            refreshInit()
        }.dimensions(rightX + 35, rightY, 35, 20).build())
        
        rightY += 25
        
        // Height
        addDrawableChild(ButtonWidget.builder(Text.literal("H-")) { 
            brushHeight = (brushHeight - 1).coerceAtLeast(1)
            refreshInit()
        }.dimensions(rightX, rightY, 35, 20).build())
        
        addDrawableChild(ButtonWidget.builder(Text.literal("H+")) { 
            brushHeight = (brushHeight + 1).coerceAtMost(32)
            refreshInit()
        }.dimensions(rightX + 35, rightY, 35, 20).build())
    }
    
    private fun refreshInit() {
        clearChildren()
        init()
    }
    
    private fun toggleMapMode() {
        mapMode = if (mapMode == MapMode.SIMPLE) MapMode.TERRAIN else MapMode.SIMPLE
        modeButton?.message = Text.literal(if (mapMode == MapMode.SIMPLE) "🗺 Схема" else "🌍 Террейн")
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // Фон
        context.fill(0, 0, width, height, colorBackground)
        
        val centerX = width / 2
        val centerY = height / 2
        
        // Определяем видимую область
        val visibleRadius = (width / chunkSize / 2 + 3).coerceAtLeast(5)
        
        val controller = AquamixDrawBot.botController
        val completedChunks = controller.getCompletedChunks()
        val queuedChunks = controller.getQueuedChunks().toSet()
        val currentTarget = controller.getTargetChunk()
        val world = client?.world
        
        // Auto-Scan (every ~1 sec)
        if (scanTickCounter++ % 20 == 0) {
             AquamixDrawBot.botController.scanAndMarkMinedChunks(client!!)
        }

        // --- RENDER LOOP ---
        // We always loop through visible chunks to show the map/terrain.
        // Optimization: When zoomed out, we disable the Grid and Fancy Overlays (Borders).
        
        val isZoomedOut = zoom < 0.5
        val renderRadius = if (isZoomedOut) visibleRadius else visibleRadius.coerceAtMost(32)
        
        for (dx in -renderRadius..renderRadius) {
            for (dz in -renderRadius..renderRadius) {
                val chunkX = (offsetX + dx).toInt()
                val chunkZ = (offsetZ + dz).toInt()
                
                val screenX = centerX + ((chunkX - offsetX) * chunkSize).toInt()
                val screenY = centerY + ((chunkZ - offsetZ) * chunkSize).toInt()
                
                // Culling
                if (screenX + chunkSize < 0 || screenX > width ||
                    screenY + chunkSize < 0 || screenY > height) continue
                
                val chunkPos = ChunkPos(chunkX, chunkZ)
                val size = chunkSize
                
                // 1. Render Content (Terrain or Simple)
                if (mapMode == MapMode.TERRAIN && world != null) {
                    val textureId = com.aquamix.drawbot.render.MapTerrainCache.getTextureId(client!!, chunkX, chunkZ)
                    if (textureId != null) {
                        context.drawTexture(textureId, screenX, screenY, size, size, 0f, 0f, 16, 16, 16, 16)
                    } else {
                         // Unloaded void
                        context.fill(screenX, screenY, screenX + size, screenY + size, colorVoid)
                    }
                } else {
                    // Simple Mode Background
                    if (colorChunkDefault != 0) {
                         context.fill(screenX + 1, screenY + 1, screenX + size, screenY + size, colorChunkDefault)
                    }
                }
                
                // 2. Overlay Status
                var baseColor = 0
                when {
                    currentTarget == chunkPos -> baseColor = colorChunkCurrent
                    chunkPos in completedChunks -> baseColor = colorChunkCompleted
                    chunkPos in queuedChunks -> baseColor = colorChunkQueued
                    chunkPos in selectedChunks -> baseColor = colorChunkSelected
                }
                
                if (baseColor != 0) {
                    if (!isZoomedOut && mapMode == MapMode.TERRAIN) {
                         // Modern: Border + Tint (Only when zoomed in)
                         val tintAlpha = 0x44 
                         val tintColor = (baseColor and 0x00FFFFFF) or (tintAlpha shl 24)
                         context.fill(screenX, screenY, screenX + size, screenY + size, tintColor)
                         
                         val borderColor = (baseColor and 0x00FFFFFF) or 0xFF000000.toInt()
                         context.drawBorder(screenX, screenY, size, size, borderColor)
                    } else {
                         // Simple: Solid Fill (Zoomed out OR Simple mode)
                         // If we are zoomed out, we want clear visibility, so full fill is better/faster
                         context.fill(screenX, screenY, screenX + size, screenY + size, baseColor)
                    }
                }
                
                // 3. Grid Lines
                // OPTIMIZATION: Hide grid when zoomed out or too small
                if (!isZoomedOut && chunkSize > 4) {
                    context.drawBorder(screenX, screenY, size, size, colorGrid)
                }
            }
        }


        
        // Brush Preview (Visual Feedback)
        // Show what chunks would be selected if clicked
        if (!isSelecting && (brushWidth > 1 || brushHeight > 1)) {
             drawBrushPreview(context, screenToChunk(mouseX.toDouble(), mouseY.toDouble()), centerX, centerY)
        }
        
        // Рисуем выделение прямоугольником
        if (isSelecting && selectStartChunk != null) {
            drawSelectionRect(context, selectStartChunk!!, screenToChunk(mouseX.toDouble(), mouseY.toDouble()), centerX, centerY)
        }
        
        // Рисуем игрока
        client?.player?.let { player ->
            val playerChunkX = player.blockPos.x shr 4
            val playerChunkZ = player.blockPos.z shr 4
            
            val playerScreenX = centerX + ((playerChunkX - offsetX) * chunkSize).toInt()
            val playerScreenY = centerY + ((playerChunkZ - offsetZ) * chunkSize).toInt()
            
            val playerBlockX = player.blockPos.x % 16
            val playerBlockZ = player.blockPos.z % 16
            
            val playerPixelX = playerScreenX + (playerBlockX / 16.0 * chunkSize).toInt()
            val playerPixelY = playerScreenY + (playerBlockZ / 16.0 * chunkSize).toInt()
            
            context.fill(playerPixelX - 2, playerPixelY - 2, playerPixelX + 3, playerPixelY + 3, colorPlayer)
        }
        
        // Информационная панель
        renderInfoPanel(context, mouseX, mouseY)
        
        // Player Marker & Super Render
        super.render(context, mouseX, mouseY, delta)
    }

    // --- Terrain Optimization ---
    // Moved to global MapTerrainCache for persistence
    
    // override fun close() {
    //    super.close()
    // }
    
    // ... Rest of existing methods (renderInfoPanel, etc.)
    private fun renderInfoPanel(context: DrawContext, mouseX: Int, mouseY: Int) {
        val panelY = height - 45
        
        context.fill(0, panelY, width, height, 0xCC000000.toInt())
        
        val controller = AquamixDrawBot.botController
        
        val statusText = if (controller.isRunning) {
            "§a● Работает: ${controller.getCurrentState()}"
        } else {
            "§7○ Остановлен"
        }
        context.drawText(textRenderer, statusText, 10, panelY + 8, colorText, true)
        
        val statsText = "Выбрано: §e${selectedChunks.size}§r | " +
                       "В очереди: §6${controller.getQueueSize()}§r | " +
                       "Выполнено: §a${controller.getCompletedChunks().size}"
        context.drawText(textRenderer, statsText, 10, panelY + 22, colorText, true)
        
        val hoverChunk = screenToChunk(mouseX.toDouble(), mouseY.toDouble())
        val chunkText = "Чанк: ${hoverChunk.x}, ${hoverChunk.z}"
        context.drawText(textRenderer, chunkText, width - textRenderer.getWidth(chunkText) - 10, panelY + 8, colorTextDim, true)
        
        val blockText = "Блоки: ${hoverChunk.minBlockX}..${hoverChunk.maxBlockX}, ${hoverChunk.minBlockZ}..${hoverChunk.maxBlockZ}"
        context.drawText(textRenderer, blockText, width - textRenderer.getWidth(blockText) - 10, panelY + 22, colorTextDim, true)
    }
    
    private fun drawSelectionRect(context: DrawContext, start: ChunkPos, end: ChunkPos, centerX: Int, centerY: Int) {
        val minX = minOf(start.x, end.x)
        val maxX = maxOf(start.x, end.x)
        val minZ = minOf(start.z, end.z)
        val maxZ = maxOf(start.z, end.z)
        
        drawRectArea(context, minX, maxX, minZ, maxZ, centerX, centerY, colorChunkSelected, true)
    }
    
    private fun drawBrushPreview(context: DrawContext, startChunk: ChunkPos, centerX: Int, centerY: Int) {
         // Brush extends +X and +Z from start
         val minX = startChunk.x
         val maxX = startChunk.x + brushWidth - 1
         val minZ = startChunk.z
         val maxZ = startChunk.z + brushHeight - 1
         
         // Use a lighter/different color for preview (e.g., white with alpha)
         val previewColor = 0xFFFFFFFF.toInt()
         drawRectArea(context, minX, maxX, minZ, maxZ, centerX, centerY, previewColor, false)
    }
    
    private fun drawRectArea(context: DrawContext, minX: Int, maxX: Int, minZ: Int, maxZ: Int, centerX: Int, centerY: Int, color: Int, isSelection: Boolean) {
        val screenX1 = centerX + ((minX - offsetX) * chunkSize).toInt()
        val screenY1 = centerY + ((minZ - offsetZ) * chunkSize).toInt()
        val screenX2 = centerX + ((maxX + 1 - offsetX) * chunkSize).toInt()
        val screenY2 = centerY + ((maxZ + 1 - offsetZ) * chunkSize).toInt()
        
        val fillAlpha = if (isSelection) 0x44 else 0x33
        val fillColor = (color and 0x00FFFFFF) or (fillAlpha shl 24)
        
        context.fill(screenX1, screenY1, screenX2, screenY2, fillColor)
        
        if (isSelection) {
             context.fill(screenX1, screenY1, screenX2, screenY1 + 2, color)
             context.fill(screenX1, screenY2 - 2, screenX2, screenY2, color)
             context.fill(screenX1, screenY1, screenX1 + 2, screenY2, color)
             context.fill(screenX2 - 2, screenY1, screenX2, screenY2, color)
        } else {
             // Dotted border or simpler for brush preview
             val borderC = (color and 0x00FFFFFF) or 0x88000000.toInt() // Semi-transparent border
             context.drawBorder(screenX1, screenY1, screenX2 - screenX1, screenY2 - screenY1, borderC)
        }
    }
    
    // Paint Selection (Drag to select)
    private var isPainting = false
    private var paintAddMode = true // true = add, false = remove

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (super.mouseClicked(mouseX, mouseY, button)) return true
        
        if (mouseY > height - 45) return false
        
        when (button) {
            0 -> {
                if (hasShiftDown()) {
                    isSelecting = true
                    selectStartChunk = screenToChunk(mouseX, mouseY)
                } else {
                    // Start Painting with Brush
                    isPainting = true
                    val chunk = screenToChunk(mouseX, mouseY)
                    
                    // Determine mode: if clicking on selected -> remove mode. Else add mode.
                    paintAddMode = chunk !in selectedChunks
                    
                    applyBrush(chunk, paintAddMode)
                }
                return true
            }
            1 -> {
                isDragging = true
                dragStartX = mouseX
                dragStartY = mouseY
                dragOffsetX = offsetX
                dragOffsetZ = offsetZ
                return true
            }
            2 -> {
                val chunk = screenToChunk(mouseX, mouseY)
                offsetX = chunk.x.toDouble()
                offsetZ = chunk.z.toDouble()
                return true
            }
        }
        
        return false
    }
    
    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        when (button) {
            0 -> {
                isPainting = false // Stop painting
                if (isSelecting && selectStartChunk != null) {
                    val endChunk = screenToChunk(mouseX, mouseY)
                    val minX = minOf(selectStartChunk!!.x, endChunk.x)
                    val maxX = maxOf(selectStartChunk!!.x, endChunk.x)
                    val minZ = minOf(selectStartChunk!!.z, endChunk.z)
                    val maxZ = maxOf(selectStartChunk!!.z, endChunk.z)
                    
                    for (x in minX..maxX) {
                        for (z in minZ..maxZ) {
                            selectedChunks.add(ChunkPos(x, z))
                        }
                    }
                    
                    isSelecting = false
                    selectStartChunk = null
                }
            }
            1 -> {
                isDragging = false
            }
        }
        return super.mouseReleased(mouseX, mouseY, button)
    }
    
    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean {
        if (button == 0 && isPainting && !hasShiftDown()) {
            val chunk = screenToChunk(mouseX, mouseY)
            applyBrush(chunk, paintAddMode)
            return true
        }
        
        if (isDragging && button == 1) {
            val dx = (mouseX - dragStartX) / chunkSize
            val dz = (mouseY - dragStartY) / chunkSize
            offsetX = dragOffsetX - dx
            offsetZ = dragOffsetZ - dz
            return true
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)
    }
    
    private fun applyBrush(startChunk: ChunkPos, add: Boolean) {
        for (dx in 0 until brushWidth) {
            for (dz in 0 until brushHeight) {
                val chunk = ChunkPos(startChunk.x + dx, startChunk.z + dz)
                if (add) {
                    selectedChunks.add(chunk)
                } else {
                    selectedChunks.remove(chunk)
                }
            }
        }
    }
    
    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val zoomFactor = if (verticalAmount > 0) 1.15 else 1 / 1.15
        zoom = (zoom * zoomFactor).coerceIn(0.3, 4.0)
        return true
    }
    
    private fun screenToChunk(mouseX: Double, mouseY: Double): ChunkPos {
        val centerX = width / 2
        val centerY = height / 2
        
        val chunkX = floor(offsetX + (mouseX - centerX) / chunkSize).toInt()
        val chunkZ = floor(offsetZ + (mouseY - centerY) / chunkSize).toInt()
        
        return ChunkPos(chunkX, chunkZ)
    }
    
    private fun startBot() {
        if (selectedChunks.isEmpty()) {
            client?.player?.sendMessage(Text.literal("§c[DrawBot] Сначала выбери чанки на карте!"), false)
            return
        }
        
        AquamixDrawBot.botController.setChunksToBreak(selectedChunks.toList())
        AquamixDrawBot.botController.start()
        close()
    }
    
    private fun stopBot() {
        AquamixDrawBot.botController.stop()
    }
    
    private fun clearSelection() {
        selectedChunks.clear()
        AquamixDrawBot.botController.clearAll()
    }
    
    private fun centerOnPlayer() {
        client?.player?.let { player ->
            offsetX = (player.blockPos.x shr 4).toDouble()
            offsetZ = (player.blockPos.z shr 4).toDouble()
        }
    }
    
    private fun saveSchematic() {
        if (selectedChunks.isEmpty()) {
            client?.player?.sendMessage(Text.literal("§c[DrawBot] Сначала выбери чанки!"), false)
            return
        }
        
        val name = schematicNameField?.text?.takeIf { it.isNotBlank() } ?: "Схематика ${System.currentTimeMillis()}"
        val schematic = SchematicManager.createFromSelection(name, selectedChunks)
        
        if (SchematicManager.save(schematic)) {
            client?.player?.sendMessage(Text.literal("§a[DrawBot] Схематика сохранена: $name (${schematic.chunkCount} чанков)"), false)
        } else {
            client?.player?.sendMessage(Text.literal("§c[DrawBot] Ошибка сохранения!"), false)
        }
    }
    
    private fun openLoadDialog() {
        client?.setScreen(SchematicListScreen(this))
    }
    
    fun loadSchematic(schematic: Schematic) {
        selectedChunks.clear()
        selectedChunks.addAll(schematic.chunks)
        
        if (schematic.chunks.isNotEmpty()) {
            val bounds = schematic.getBounds()
            offsetX = ((bounds.first.x + bounds.second.x) / 2.0)
            offsetZ = ((bounds.first.z + bounds.second.z) / 2.0)
        }
        
        client?.player?.sendMessage(
            Text.literal("§a[DrawBot] Загружена: ${schematic.name} (${schematic.chunkCount} чанков)"), 
            false
        )
    }
    
    override fun shouldPause() = false
    
    // override fun close() {
    //    super.close()
    // }
}
