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
        
        // Рисуем чанки
        for (dx in -visibleRadius..visibleRadius) {
            for (dz in -visibleRadius..visibleRadius) {
                val chunkX = (offsetX + dx).toInt()
                val chunkZ = (offsetZ + dz).toInt()
                
                val screenX = centerX + ((chunkX - offsetX) * chunkSize).toInt()
                val screenY = centerY + ((chunkZ - offsetZ) * chunkSize).toInt()
                
                // Пропускаем чанки за пределами экрана
                if (screenX + chunkSize < 0 || screenX > width ||
                    screenY + chunkSize < 0 || screenY > height) continue
                
                val chunkPos = ChunkPos(chunkX, chunkZ)
                val size = chunkSize
                
                // Рендеринг содержимого чанка
                if (mapMode == MapMode.TERRAIN && world != null) {
                    if (world.chunkManager.isChunkLoaded(chunkX, chunkZ)) {
                        terrainRenderer.renderChunk(context, chunkX, chunkZ, screenX, screenY, size)
                    } else {
                        context.fill(screenX, screenY, screenX + size, screenY + size, colorVoid)
                    }
                } else {
                    // Simple Mode Background
                    val baseColor = if (mapMode == MapMode.SIMPLE) colorChunkDefault else 0x00000000
                    if (baseColor != 0) {
                        context.fill(screenX + 1, screenY + 1, screenX + size, screenY + size, baseColor)
                    }
                }
                
                // Наложение статуса (Overlay)
                var overlayColor = 0
                when {
                    currentTarget == chunkPos -> overlayColor = colorChunkCurrent
                    chunkPos in completedChunks -> overlayColor = colorChunkCompleted
                    chunkPos in queuedChunks -> overlayColor = colorChunkQueued
                    chunkPos in selectedChunks -> overlayColor = colorChunkSelected
                }
                
                if (overlayColor != 0) {
                    val alpha = if (mapMode == MapMode.TERRAIN) 0x66000000.toInt() else 0xFF000000.toInt()
                    val finalColor = (overlayColor and 0x00FFFFFF) or alpha
                    context.fill(screenX, screenY, screenX + size, screenY + size, finalColor)
                }
                
                // Сетка
                if (mapMode == MapMode.SIMPLE || size >= 32) {
                     val gridColor = if (mapMode == MapMode.TERRAIN) 0x44000000.toInt() else colorGrid
                     context.fill(screenX + size, screenY, screenX + size + 1, screenY + size + 1, gridColor)
                     context.fill(screenX, screenY + size, screenX + size + 1, screenY + size + 1, gridColor)
                }
            }
        }
        
        // Рисуем прямоугольник выделения
        if (isSelecting && selectStartChunk != null) {
            val currentChunk = screenToChunk(mouseX.toDouble(), mouseY.toDouble())
            drawSelectionRect(context, selectStartChunk!!, currentChunk, centerX, centerY)
        }
        
        // Рисуем позицию игрока
        client?.player?.let { player ->
            val playerChunkX = player.blockPos.x shr 4
            val playerChunkZ = player.blockPos.z shr 4
            
            // Позиция внутри чанка (0-1)
            val inChunkX = (player.x % 16) / 16.0
            val inChunkZ = (player.z % 16) / 16.0
            
            val playerScreenX = centerX + ((playerChunkX - offsetX + inChunkX) * chunkSize).toInt()
            val playerScreenZ = centerY + ((playerChunkZ - offsetZ + inChunkZ) * chunkSize).toInt()
            
            // Маркер игрока
            val markerSize = 6
            context.fill(
                playerScreenX - markerSize, playerScreenZ - markerSize,
                playerScreenX + markerSize, playerScreenZ + markerSize,
                colorPlayer
            )
            
            // Направление
            val yawRad = Math.toRadians(player.yaw.toDouble() + 180)
            val dirX = (kotlin.math.sin(yawRad) * 12).toInt()
            val dirZ = (-kotlin.math.cos(yawRad) * 12).toInt()
            context.fill(
                playerScreenX + dirX - 2, playerScreenZ + dirZ - 2,
                playerScreenX + dirX + 2, playerScreenZ + dirZ + 2,
                colorPlayer
            )
        }
        
        renderInfoPanel(context, mouseX, mouseY)
        
        super.render(context, mouseX, mouseY, delta)
    }

    // --- Terrain Optimization ---
    private val terrainRenderer = TerrainRenderer()
    
    override fun close() {
        terrainRenderer.clear() // Освобождаем текстуры
        super.close()
    }
    
    private inner class TerrainRenderer {
        private val textures = mutableMapOf<ChunkPos, net.minecraft.client.texture.NativeImageBackedTexture>()
        private val identifiers = mutableMapOf<ChunkPos, net.minecraft.util.Identifier>()
        
        fun renderChunk(context: DrawContext, chunkX: Int, chunkZ: Int, x: Int, y: Int, size: Int) {
            val pos = ChunkPos(chunkX, chunkZ)
            
            // Получаем или создаем текстуру
            val identifier = identifiers.getOrPut(pos) {
                val texture = generateTexture(chunkX, chunkZ)
                textures[pos] = texture
                val id = client!!.textureManager.registerDynamicTexture("chunk_${chunkX}_${chunkZ}", texture)
                id
            }
            
            // Рисуем текстуру целиком
            context.drawTexture(identifier, x, y, size, size, 0f, 0f, 16, 16, 16, 16)
        }
        
        private fun generateTexture(chunkX: Int, chunkZ: Int): net.minecraft.client.texture.NativeImageBackedTexture {
            val img = net.minecraft.client.texture.NativeImage(16, 16, true)
            val world = client!!.world!!
            val heights = IntArray(256)
            
            // 1. Сначала собираем высоты для теней
            for (z in 0 until 16) {
                for (x in 0 until 16) {
                    val worldX = chunkX * 16 + x
                    val worldZ = chunkZ * 16 + z
                    heights[z * 16 + x] = world.getTopY(Heightmap.Type.WORLD_SURFACE, worldX, worldZ)
                }
            }
            
            // 2. Генерируем пиксели
            for (z in 0 until 16) {
                for (x in 0 until 16) {
                    val idx = z * 16 + x
                    val h = heights[idx]
                    
                    val worldX = chunkX * 16 + x
                    val worldZ = chunkZ * 16 + z
                    val blockPos = net.minecraft.util.math.BlockPos(worldX, h - 1, worldZ)
                    
                    val state = world.getBlockState(blockPos)
                    val mapColor = state.getMapColor(world, blockPos)
                    
                    // Базовый цвет
                    if (mapColor == MapColor.CLEAR) {
                        img.setColor(x, z, 0) // Прозрачный
                        continue
                    }
                    
                    var color = mapColor.color
                    
                    // --- Effects ---
                    
                    // 1. Shading (Тени от высоты)
                    // Сравниваем с блоком выше (z-1)
                    var shading = 0
                    if (z > 0) {
                        val hAbove = heights[(z - 1) * 16 + x]
                        if (h < hAbove) shading = -15 // Темнее
                        if (h > hAbove) shading = 15 // Светлее
                    }
                    
                    // Применяем шейдинг
                    val r = ((color shr 16) and 0xFF) + shading
                    val g = ((color shr 8) and 0xFF) + shading
                    val b = (color and 0xFF) + shading
                    
                    // Clamp
                    val finalR = r.coerceIn(0, 255)
                    val finalG = g.coerceIn(0, 255)
                    val finalB = b.coerceIn(0, 255)
                    
                    // Упаковка цвета (RGBA / ABGR)
                    // NativeImage.setColor обычно принимает ABGR
                    // MapColor.color это int ARGB (или RGB)
                    // Нам нужно 0xAABBGGRR (Little Endian)
                    
                    // Alpha = 255 (Fully Opaque)
                    var alpha = 0xFF
                    
                    // Water Transparency (MapColor.BLUE usually around ID 12)
                    // Простая проверка по цвету (около синего)
                    if (mapColor == MapColor.OAK_TAN || mapColor == MapColor.WATER_BLUE || mapColorIdIsWater(mapColor)) {
                         alpha = 0xAA // Semi transparency
                    }
                    
                    val finalColor = (alpha shl 24) or (finalB shl 16) or (finalG shl 8) or finalR
                    
                    img.setColor(x, z, finalColor)
                }
            }
            
            val texture = net.minecraft.client.texture.NativeImageBackedTexture(img)
            return texture
        }
        
        // Helper to guess water
        private fun mapColorIdIsWater(color: MapColor): Boolean {
             return color.id == 12 // Water
        }
        
        fun clear() {
            // Освобождаем ресурсы
            identifiers.forEach { (_, id) -> 
                 client!!.textureManager.destroyTexture(id)
            }
            textures.forEach { (_, tex) -> tex.close() }
            textures.clear()
            identifiers.clear()
        }
    }
    
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
        
        val screenX1 = centerX + ((minX - offsetX) * chunkSize).toInt()
        val screenY1 = centerY + ((minZ - offsetZ) * chunkSize).toInt()
        val screenX2 = centerX + ((maxX + 1 - offsetX) * chunkSize).toInt()
        val screenY2 = centerY + ((maxZ + 1 - offsetZ) * chunkSize).toInt()
        
        context.fill(screenX1, screenY1, screenX2, screenY2, 0x44ff4757)
        
        context.fill(screenX1, screenY1, screenX2, screenY1 + 2, colorChunkSelected)
        context.fill(screenX1, screenY2 - 2, screenX2, screenY2, colorChunkSelected)
        context.fill(screenX1, screenY1, screenX1 + 2, screenY2, colorChunkSelected)
        context.fill(screenX2 - 2, screenY1, screenX2, screenY2, colorChunkSelected)
    }
    
    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (super.mouseClicked(mouseX, mouseY, button)) return true
        
        if (mouseY > height - 45) return false
        
        when (button) {
            0 -> {
                if (hasShiftDown()) {
                    isSelecting = true
                    selectStartChunk = screenToChunk(mouseX, mouseY)
                } else {
                    val chunk = screenToChunk(mouseX, mouseY)
                    if (chunk in selectedChunks) {
                        selectedChunks.remove(chunk)
                    } else {
                        selectedChunks.add(chunk)
                    }
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
        if (isDragging && button == 1) {
            val dx = (mouseX - dragStartX) / chunkSize
            val dz = (mouseY - dragStartY) / chunkSize
            offsetX = dragOffsetX - dx
            offsetZ = dragOffsetZ - dz
            return true
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)
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
