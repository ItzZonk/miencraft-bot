package com.aquamix.drawbot.automation

import com.aquamix.drawbot.AquamixDrawBot
import com.aquamix.drawbot.anticheat.HumanSimulator
import com.aquamix.drawbot.config.ModConfig
import com.aquamix.drawbot.features.TelegramNotifier
import com.aquamix.drawbot.navigation.RouteOptimizer
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text

/**
 * Главный контроллер бота
 * Управляет всей логикой автоматизации
 * 
 * Улучшено: sealed class FSM, AgenticLoop самокоррекция, анти-чит задержки
 */
class BotController {
    private val stateMachine = StateMachine()
    val chunkBreaker = ChunkBreaker()
    val flightController = FlightController()
    private val routeOptimizer = RouteOptimizer()
    val inventoryManager = InventoryManager()
    
    var isRunning = false
        private set
    
    // Очередь чанков для обработки
    private var chunksQueue: MutableList<ChunkPos> = mutableListOf()
    
    init {
        // Загружаем сохраненную очередь при запуске
        val savedQueue = AquamixDrawBot.progressTracker.getQueuedChunks()
        if (savedQueue.isNotEmpty()) {
            chunksQueue.addAll(savedQueue)
            AquamixDrawBot.LOGGER.info("Restored ${savedQueue.size} chunks from persistence")
        }
    }
    
    // Завершённые чанки (для отображения на карте)
    private val completedChunks: MutableSet<ChunkPos> = mutableSetOf()
    
    // Статистика
    var totalChunksProcessed = 0
        private set
    
    // Флаг подтверждения для текущего состояния
    private var confirmationReceived = false
    
    /**
     * Переключить состояние бота
     */
    fun toggle() {
        if (isRunning) stop() else start()
    }
    
    /**
     * Запустить бота
     */
    fun start() {
        if (chunksQueue.isEmpty()) {
            sendMessage("§c[DrawBot] Очередь чанков пуста! Открой карту (M) и выбери чанки.")
            return
        }
        
        val firstTarget = chunksQueue.firstOrNull() ?: return
        
        isRunning = true
        startTime = System.currentTimeMillis()
        stateMachine.transition(BotState.FlyingToChunk(firstTarget))
        sendMessage("§a[DrawBot] Запущен! Чанков в очереди: ${chunksQueue.size}")
        
        AquamixDrawBot.LOGGER.info("Bot started with ${chunksQueue.size} chunks in queue")
        TelegramNotifier.sendNotification("🚀 Бот запущен!\nОчередь: ${chunksQueue.size} чанков")
    }
    
    /**
     * Остановить бота
     */
    fun stop() {
        isRunning = false
        stateMachine.reset()
        
        val client = MinecraftClient.getInstance()
        flightController.stopMovement(client)
        flightController.reset()
        
        // КЛЮЧЕВОЕ: восстановить оригинальный KeyboardInput
        com.aquamix.drawbot.input.InputOverrideHandler.reset(client)
        
        sendMessage("§e[DrawBot] Остановлен")
        
        AquamixDrawBot.LOGGER.info("Bot stopped. Processed: $totalChunksProcessed chunks")
        TelegramNotifier.sendNotification("⏹ Бот остановлен.")
    }
    
    /**
     * Установить список чанков для обработки
     */
    fun setChunksToBreak(chunks: List<ChunkPos>) {
        val playerChunk = getPlayerChunkPos() ?: ChunkPos(0, 0)
        
        // Оптимизируем маршрут
        chunksQueue = routeOptimizer.optimize(chunks, playerChunk).toMutableList()
        
        sendMessage("§a[DrawBot] Загружено ${chunks.size} чанков, маршрут оптимизирован")
        AquamixDrawBot.LOGGER.info("Loaded ${chunks.size} chunks, optimized route from $playerChunk")
        
        // Save
        AquamixDrawBot.progressTracker.setQueuedChunks(chunksQueue)
        AquamixDrawBot.progressTracker.save()
    }
    
    /**
     * Добавить чанки к существующей очереди
     */
    fun addChunks(chunks: List<ChunkPos>) {
        val newChunks = chunks.filter { it !in chunksQueue && it !in completedChunks }
        chunksQueue.addAll(newChunks)
        
        // Переоптимизируем маршрут
        val playerChunk = getPlayerChunkPos() ?: ChunkPos(0, 0)
        chunksQueue = routeOptimizer.optimize(chunksQueue, playerChunk).toMutableList()
        
        sendMessage("§a[DrawBot] Добавлено ${newChunks.size} чанков")
        
        // Save
        AquamixDrawBot.progressTracker.setQueuedChunks(chunksQueue)
        AquamixDrawBot.progressTracker.save()
    }
    
    /**
     * Главный цикл бота - вызывается каждый тик
     * Использует sealed class matching для type-safe обработки состояний
     */
    fun tick(client: MinecraftClient) {
        // КЛЮЧЕВОЕ: Baritone-style input replacement
        // Заменяет player.input на BotMovementInput когда бот активен
        com.aquamix.drawbot.input.InputOverrideHandler.onTick(client)
        
        if (!isRunning) return
        
        val player = client.player ?: return
        val config = ModConfig.data
        
        // CRITICAL: Check for falling and auto-recover
        if (flightController.checkAndRecoverFromFall(client)) {
            // Bot is recovering from fall, skip normal state handling
            return
        }
        
        when (val state = stateMachine.currentState) {
            is BotState.Idle -> {
                // Ничего не делаем
            }
            
            is BotState.FlyingToChunk -> {
                // Проверка инвентаря перед вылетом
                if (!inventoryManager.checkAndEquip(client)) {
                    handleError(BotError.NoBurInInventory, state)
                    return
                }

                // Активируем /fly
                flightController.ensureFlyActive(client)
                
                // ALWAYS try to find target block first (Zero Height Limit logic)
                // If chunk is loaded, get target immediately
                if (client.world != null && client.world!!.chunkManager.isChunkLoaded(state.target.x, state.target.z)) {
                    val targetBlock = chunkBreaker.getTarget(client, state.target)
                    if (targetBlock != null) {
                         AquamixDrawBot.LOGGER.info("Direct flight to block ${targetBlock.toShortString()}")
                         stateMachine.transition(BotState.FlyingToBlock(state.target, targetBlock))
                         return
                    }
                }
                
                // Fallback: Fly to chunk center (only if chunk not loaded or no block found)
                if (flightController.flyToChunk(client, state.target)) {
                    stateMachine.transition(BotState.PlacingBur(state.target))
                }
            }
            
            is BotState.FlyingToBlock -> {
                if (!inventoryManager.checkAndEquip(client)) {
                    handleError(BotError.NoBurInInventory, state)
                    return
                }
                flightController.ensureFlyActive(client)
                
                if (flightController.flyToBlock(client, state.targetBlock)) {
                    AquamixDrawBot.LOGGER.debug("Directly arrived at block ${state.targetBlock} in chunk ${state.targetChunk}")
                    // We are at the block, skip Landing state and go straight to placing
                    stateMachine.transition(BotState.PlacingBur(state.targetChunk))
                }
            }
            
            is BotState.Landing -> {
                // Проверяем приземление с таймаутом 5s
                if (flightController.landInChunk(client, state.target)) {
                    AquamixDrawBot.LOGGER.debug("Landed in chunk ${state.target}")
                    stateMachine.transition(BotState.PlacingBur(state.target))
                } else if (stateMachine.isTimedOut(5000)) {
                    handleError(
                        BotError.Timeout("landing", stateMachine.timeInState()),
                        state
                    )
                }
            }
            
            is BotState.PlacingBur -> {
                // Zero-Latency: Check immediately
                val targetBlock = chunkBreaker.getTarget(client, state.target)
                if (targetBlock != null) {
                    // Don't update rotation here, let ChunkBreaker aim
                    flightController.hoverAbove(client, targetBlock, updateRotation = false)
                }
                
                // REMOVED: val humanDelay = HumanSimulator.randomDelay(100)
                // Speed: GO GO GO
                
                if (chunkBreaker.placeBur(client, state.target)) {
                    stateMachine.transition(BotState.WaitingForMenu(state.target))
                }
                
                // Проверяем если меню уже открылось случайно (лагануло)
                if (chunkBreaker.isBurMenuOpen(client)) {
                    stateMachine.transition(BotState.WaitingForMenu(state.target))
                }
                
                // Таймаут 1 сек (быстро сдаёмся и пробуем снова)
                if (stateMachine.isTimedOut(1000)) {
                    chunkBreaker.reset() // Clear cache and try again
                    
                    // Max 10 retries - if still no valid block, skip this chunk
                    if (state.retryCount >= 10) {
                        AquamixDrawBot.LOGGER.warn("Chunk ${state.target} has no valid blocks after 10 retries, skipping!")
                        sendMessage("§e[DrawBot] Пропускаю чанк ${state.target} - нет подходящих блоков")
                        completedChunks.add(state.target)
                        chunksQueue.remove(state.target)
                        val nextTarget = chunksQueue.firstOrNull()
                        if (nextTarget != null) {
                            stateMachine.transition(BotState.FlyingToChunk(nextTarget))
                        } else {
                            stateMachine.transition(BotState.Idle)
                            sendMessage("§a[DrawBot] Все чанки обработаны!")
                        }
                    } else {
                        stateMachine.transition(BotState.PlacingBur(state.target, state.retryCount + 1))
                    }
                }
            }
            
            is BotState.WaitingForMenu -> {
                // Stabilize position (Stop moving)
                flightController.stopMovement(client)
                
                // Сразу проверяем меню
                if (chunkBreaker.isBurMenuOpen(client)) {
                    stateMachine.transition(BotState.ClickingMenu(state.target))
                }
                
                // Wait 5s max (increased for lag)
                if (stateMachine.isTimedOut(5000)) {
                    handleError(
                        BotError.MenuNotFound(config.bur.menuTitle),
                        state
                    )
                }
            }
            
            is BotState.ClickingMenu -> {
                // Stabilize position (Stop moving)
                flightController.stopMovement(client)
                
                // REMOVED: val clickDelay = HumanSimulator.randomDelay(config.timing.menuClickDelay)
                // Zero Latency Click
                
                if (chunkBreaker.clickBreakAll(client)) {
                    // USER REQUEST: Fly immediately, don't wait for confirmation
                    chunkBreaker.closeMenu(client)
                    completeCurrentChunk(state.target)
                    
                    val nextTarget = chunksQueue.firstOrNull()
                    if (nextTarget != null) {
                        flightController.sendFlyCommand(client)
                        stateMachine.transition(BotState.FlyingToChunk(nextTarget))
                    } else {
                        finishTask()
                    }
                    return
                }
                
                if (stateMachine.isTimedOut(3000)) {
                    chunkBreaker.closeMenu(client)
                    handleError(
                        BotError.ButtonNotFound(config.bur.breakButtonPattern),
                        state
                    )
                }
            }
            
            is BotState.WaitingConfirmation -> {
                // Stabilize position (Stop moving)
                flightController.stopMovement(client)
                
                // Закрываем меню быстро
                // REMOVED: val closeDelay = HumanSimulator.randomDelay(100)
                if (client.currentScreen != null) {
                    chunkBreaker.closeMenu(client)
                }
                
                val waitTime = config.timing.chunkBreakWait.coerceAtMost(100L)
                
                if (confirmationReceived || stateMachine.timeInState() > waitTime) {
                    completeCurrentChunk(state.target)
                    
                    // Следующий чанк
                    val nextTarget = chunksQueue.firstOrNull()
                    if (nextTarget != null) {
                        flightController.sendFlyCommand(client)
                        stateMachine.transition(BotState.FlyingToChunk(nextTarget))
                    } else {
                        sendMessage("§a[DrawBot] Все чанки обработаны! Всего: $totalChunksProcessed")
                        TelegramNotifier.sendNotification("✅ Задача выполнена!\nОбработано чанков: $totalChunksProcessed")
                        stop()
                    }
                }
            }
            
            is BotState.MovingToNext -> {
                val nextTarget = chunksQueue.firstOrNull()
                if (nextTarget != null) {
                    stateMachine.transition(BotState.FlyingToChunk(nextTarget))
                } else {
                    stop()
                }
            }
            
            is BotState.Ascending -> {
                // Ascend to safe height with full pathfinding and obstacle avoidance
                flightController.ensureFlyActive(client)
                
                // flightController.ascendSafely handles:
                // 1. Pathfinding (if stuck/obstructed)
                // 2. Obstacle avoidance
                // 3. Staying centered locally (gentle nudge)
                flightController.ascendSafely(client, state.targetHeight)
                
                // Check if reached target height
                if (player.y >= state.targetHeight) {
                    val nextTarget = chunksQueue.firstOrNull()
                    if (nextTarget != null) {
                        stateMachine.transition(BotState.FlyingToChunk(nextTarget))
                    } else {
                        finishTask()
                    }
                }
                
                // Timeout after 15s (increased from 10s for safer navigation)
                if (stateMachine.isTimedOut(15000)) {
                    AquamixDrawBot.LOGGER.warn("Ascending timeout, moving to next anyway")
                    val nextTarget = chunksQueue.firstOrNull()
                    if (nextTarget != null) {
                        stateMachine.transition(BotState.FlyingToChunk(nextTarget))
                    } else {
                        finishTask()
                    }
                }
            }
            
            is BotState.SelfHealing -> {
                // Агентский цикл самокоррекции
                AquamixDrawBot.LOGGER.info("[SelfHealing] Attempt ${state.healingAttempt}: ${state.error.message}")
                
                // Даём время на "размышление"
                val thinkDelay = HumanSimulator.thinkingDelay(200, 500)
                if (stateMachine.timeInState() < thinkDelay) return
                
                val recoveryState = AgenticLoop.handleError(
                    state.error,
                    state.previousState,
                    state.healingAttempt
                )
                
                if (recoveryState is BotState.Idle) {
                    // NEVER GIVE UP - reset and retry from scratch
                    sendMessage("§e[DrawBot] Макс попытки восстановления, сброс и перезапуск...")
                    chunkBreaker.reset()
                    val target = stateMachine.getTargetChunk() ?: return
                    stateMachine.transition(BotState.PlacingBur(target, 0))
                } else {
                    stateMachine.transition(recoveryState)
                }
            }
        }
    }
    
    /**
     * Обработка ошибки через AgenticLoop
     */
    private fun handleError(error: BotError, currentState: BotState) {
        AquamixDrawBot.LOGGER.warn("[BotController] Error: ${error.message}")
        
        if (error is BotError.NoBurInInventory) {
            stop()
            TelegramNotifier.sendNotification("⚠️ Ошибка: Закончились БУРы (End Portal Frame)!")
            return
        }
        
        // Переходим в режим самокоррекции
        stateMachine.transition(BotState.SelfHealing(error, currentState, 1))
    }
    
    /**
     * Завершить обработку текущего чанка
     */
    private fun completeCurrentChunk(chunk: ChunkPos) {
        completedChunks.add(chunk)
        chunksQueue.removeFirst()
        totalChunksProcessed++
        
        AquamixDrawBot.progressTracker.markCompleted(chunk)
        AquamixDrawBot.progressTracker.setQueuedChunks(chunksQueue) // Sync queue state
        AquamixDrawBot.progressTracker.save()
        
        sendMessage("§a[DrawBot] Чанк ${chunk.x}, ${chunk.z} сломан! Осталось: ${chunksQueue.size}")
        
        // REMOVED: Ascend to safe height. Now we fly directly.
        if (chunksQueue.isNotEmpty()) {
            val nextTarget = chunksQueue.firstOrNull()
            if (nextTarget != null) {
                // OPTIMIZATION: Try to find target block in next chunk NOW (while in current chunk)
                // This allows flying directly to the block instead of the chunk center
                val client = MinecraftClient.getInstance()
                // We reset chunk breaker to ensure we look for a new target
                chunkBreaker.reset()
                
                // Note: We might be too far to load the chunk fully or find the block if it's not rendered.
                // But if we can find it, it's a huge speedup.
                // We pass the nextChunk to getTarget. 
                // Since 'getTarget' uses world.getBlockState, it works if chunk is loaded.
                
                // Just peek, don't force exhaustive scan yet if not loaded
                val world = client.world
                if (world != null && world.chunkManager.isChunkLoaded(nextTarget.x, nextTarget.z)) {
                    val targetBlock = chunkBreaker.getTarget(client, nextTarget)
                    if (targetBlock != null) {
                         AquamixDrawBot.LOGGER.info("Optimized flight: Direct to block ${targetBlock.toShortString()}")
                         stateMachine.transition(BotState.FlyingToBlock(nextTarget, targetBlock))
                    } else {
                         stateMachine.transition(BotState.FlyingToChunk(nextTarget))
                    }
                } else {
                    stateMachine.transition(BotState.FlyingToChunk(nextTarget))
                }
            } else {
                finishTask()
            }
        } else {
            finishTask()
        }
    }
    
    /**
     * Обработка сообщений чата для определения подтверждения
     */
    fun onChatMessage(message: String) {
        if (!isRunning) return
        
        val pattern = ModConfig.data.bur.confirmationPattern
        val regex = Regex(pattern, RegexOption.IGNORE_CASE)
        
        if (regex.containsMatchIn(message)) {
            AquamixDrawBot.LOGGER.debug("Confirmation received: $message")
            confirmationReceived = true
        }
    }
    
    
    /**
     * Получить позицию игрока в координатах чанков
     */
    private fun getPlayerChunkPos(): ChunkPos? {
        val player = MinecraftClient.getInstance().player ?: return null
        return ChunkPos.fromBlockPos(player.blockPos.x, player.blockPos.z)
    }
    
    /**
     * Отправить сообщение игроку
     */
    private fun sendMessage(text: String) {
        MinecraftClient.getInstance().player?.sendMessage(
            Text.literal(text), false
        )
    }

    /**
     * Сканирует загруженные чанки и помечает "пустые" (выкопанные) как выполненные
     * Вызывается из GUI карты
     */
    fun scanAndMarkMinedChunks(client: MinecraftClient) {
        val world = client.world ?: return
        val radius = client.options.viewDistance.value
        val playerChunk = getPlayerChunkPos() ?: return
        
        var addedCount = 0
        
        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                val chunkX = playerChunk.x + dx
                val chunkZ = playerChunk.z + dz
                
                if (world.chunkManager.isChunkLoaded(chunkX, chunkZ)) {
                    val chunk = world.getChunk(chunkX, chunkZ)
                    
                    // Эвристика: если центральный блок чанка на высоте 0 (или ниже) - воздух/вода
                    // Проверяем несколько точек для надежности
                    var isEmpty = true
                    for (x in 0..15 step 4) {
                        for (z in 0..15 step 4) {
                            val topY = chunk.getHeightmap(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING).get(x, z)
                            // Если высота больше -50, значит там что-то еще есть (уровень моря 63, дно -64)
                            if (topY > -50) {
                                isEmpty = false
                                break
                            }
                        }
                        if (!isEmpty) break
                    }
                    
                    if (isEmpty) {
                        val pos = ChunkPos(chunkX, chunkZ)
                        if (pos !in completedChunks) {
                            completedChunks.add(pos)
                            addedCount++
                        }
                    }
                }
            }
        }
        
        if (addedCount > 0) {
            AquamixDrawBot.progressTracker.markBatchCompleted(completedChunks) // Нужно добавить этот метод в Tracker
            AquamixDrawBot.progressTracker.save()
            // sendMessage("§a[Scanner] Отмечено $addedCount чанков как выкопанные")
        }
    }

    
    // === Геттеры для GUI ===
    
    /**
     * Toggle chunk completion status manually (for map UI)
     */
    fun toggleChunkCompletion(chunk: ChunkPos) {
        if (chunk in completedChunks) {
            completedChunks.remove(chunk)
            AquamixDrawBot.LOGGER.info("Chunk $chunk marked as NOT completed")
        } else {
            completedChunks.add(chunk)
            chunksQueue.remove(chunk) // Also remove from queue if present
            AquamixDrawBot.LOGGER.info("Chunk $chunk marked as COMPLETED")
        }
    }
    
    fun getCompletedChunks(): Set<ChunkPos> = completedChunks.toSet()
    
    fun getQueuedChunks(): List<ChunkPos> = chunksQueue.toList()
    
    fun getCurrentState(): BotState = stateMachine.currentState
    
    fun getTargetChunk(): ChunkPos? = stateMachine.getTargetChunk()
    
    fun getQueueSize(): Int = chunksQueue.size
    
    fun clearCompleted() {
        completedChunks.clear()
        AquamixDrawBot.progressTracker.clearCompleted()
    }

    // Таймер
    private var startTime = 0L
    
    fun getFormattedDuration(): String {
        if (!isRunning) return "00:00"
        val duration = System.currentTimeMillis() - startTime
        val seconds = duration / 1000
        val s = seconds % 60
        val m = (seconds / 60) % 60
        val h = seconds / 3600
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    private fun finishTask() {
        val duration = getFormattedDuration()
        sendMessage("§a[DrawBot] Все чанки обработаны! Всего: $totalChunksProcessed")
        sendMessage("§e[DrawBot] ⏱ Затрачено времени: $duration")
        TelegramNotifier.sendNotification("✅ Задача выполнена!\nОбработано: $totalChunksProcessed\nВремя: $duration")
        stop()
    }

    /**
     * Полная очистка состояния (очередь, выполненные, прогресс)
     * Используется кнопкой "Очистить" в GUI
     */
    fun clearAll() {
        stop()
        chunksQueue.clear()
        completedChunks.clear()
        AquamixDrawBot.progressTracker.clear()
        AquamixDrawBot.LOGGER.info("Bot state fully cleared")
        sendMessage("§e[DrawBot] Прогресс и очередь очищены.")
    }
}
