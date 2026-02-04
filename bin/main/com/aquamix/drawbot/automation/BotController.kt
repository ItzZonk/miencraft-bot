package com.aquamix.drawbot.automation

import com.aquamix.drawbot.AquamixDrawBot
import com.aquamix.drawbot.config.ModConfig
import com.aquamix.drawbot.features.TelegramNotifier
import com.aquamix.drawbot.navigation.RouteOptimizer
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text

/**
 * Главный контроллер бота
 * Управляет всей логикой автоматизации
 */
class BotController {
    private val stateMachine = StateMachine()
    private val chunkBreaker = ChunkBreaker()
    private val flightController = FlightController()
    private val routeOptimizer = RouteOptimizer()
    private val inventoryManager = InventoryManager()
    
    var isRunning = false
        private set
    
    // Очередь чанков для обработки
    private var chunksQueue: MutableList<ChunkPos> = mutableListOf()
    
    // Завершённые чанки (для отображения на карте)
    private val completedChunks: MutableSet<ChunkPos> = mutableSetOf()
    
    // Статистика
    var totalChunksProcessed = 0
        private set
    
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
        
        isRunning = true
        stateMachine.transition(BotState.FLYING_TO_CHUNK)
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
        flightController.stopMovement(MinecraftClient.getInstance())
        flightController.reset()
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
    }
    
    /**
     * Главный цикл бота - вызывается каждый тик
     */
    fun tick(client: MinecraftClient) {
        if (!isRunning) return
        
        val player = client.player ?: return
        val config = ModConfig.data
        
        when (stateMachine.currentState) {
            BotState.IDLE -> {
                // Ничего не делаем
            }
            
            BotState.FLYING_TO_CHUNK -> {
                // Проверка инвентаря перед вылетом
                if (!inventoryManager.checkAndEquip(client)) {
                    stop()
                    TelegramNotifier.sendNotification("⚠️ Ошибка: Закончились БУРы (End Portal Frame)!")
                    return
                }

                val target = chunksQueue.firstOrNull()
                if (target == null) {
                    sendMessage("§a[DrawBot] Все чанки обработаны! Всего: $totalChunksProcessed")
                    TelegramNotifier.sendNotification("✅ Задача выполнена!\nОбработано чанков: $totalChunksProcessed")
                    stop()
                    return
                }
                
                stateMachine.targetChunk = target
                
                // Активируем /fly
                flightController.ensureFlyActive(client)
                
                // Летим к чанку
                if (flightController.flyToChunk(client, target)) {
                    AquamixDrawBot.LOGGER.debug("Arrived at chunk $target, landing...")
                    stateMachine.transition(BotState.LANDING)
                }
            }
            
            BotState.LANDING -> {
                val target = stateMachine.targetChunk ?: return
                
                // Проверяем приземление
                // Timeout 10s -> 5s для более быстрой реакции на застревание
                if (flightController.landInChunk(client, target)) {
                    AquamixDrawBot.LOGGER.debug("Landed in chunk $target")
                    stateMachine.transition(BotState.PLACING_BUR)
                } else if (stateMachine.isTimedOut(5000)) {
                    AquamixDrawBot.LOGGER.warn("Landing timeout, retrying...")
                    stateMachine.transition(BotState.FLYING_TO_CHUNK)
                }
            }
            
            BotState.PLACING_BUR -> {
                // Принудительно смотрим вниз для точной установки
                client.player?.pitch = 90f
                
                // Пытаемся поставить БУР
                if (chunkBreaker.placeBur(client)) {
                    stateMachine.transition(BotState.WAITING_FOR_MENU)
                }
                
                // Таймаут уменьшен до 3 сек
                if (stateMachine.isTimedOut(3000)) {
                    if (stateMachine.incrementRetry() > 3) {
                         // ... logic ...
                        sendMessage("§c[DrawBot] Не удалось использовать БУР, пропускаем чанк")
                        skipCurrentChunk()
                    } else {
                        stateMachine.transition(BotState.PLACING_BUR)
                    }
                }
            }
            
            BotState.WAITING_FOR_MENU -> {
                // Сразу проверяем меню
                if (chunkBreaker.isBurMenuOpen(client)) {
                    stateMachine.transition(BotState.CLICKING_MENU)
                }
                
                // Wait 2s max
                if (stateMachine.isTimedOut(2000)) {
                    AquamixDrawBot.LOGGER.warn("Menu timeout, retrying BUR placement")
                    stateMachine.transition(BotState.PLACING_BUR)
                }
            }
            
            BotState.CLICKING_MENU -> {
                // Min delay reduced to quick click (1 tick or config dependent)
                 // Если конфиг позволяет, делаем почти мгновенно
                if (stateMachine.timeInState() < 50) { // 50ms hardcoded min
                    return
                }
                
                if (chunkBreaker.clickBreakAll(client)) {
                    stateMachine.transition(BotState.WAITING_CONFIRMATION)
                }
                
                if (stateMachine.isTimedOut(3000)) {
                    chunkBreaker.closeMenu(client)
                    stateMachine.transition(BotState.PLACING_BUR)
                }
            }
            
            BotState.WAITING_CONFIRMATION -> {
                // Закрываем меню быстро (100ms)
                if (client.currentScreen != null && stateMachine.timeInState() > 100) {
                    chunkBreaker.closeMenu(client)
                }
                
                val confirmed = stateMachine.getFlag("confirmed")
                // Wait time reduced or taken from config
                val waitTime = config.timing.chunkBreakWait.coerceAtMost(5000L) // Max 5s check
                
                if (confirmed || stateMachine.timeInState() > waitTime) {
                    val completed = stateMachine.targetChunk!!
                    completedChunks.add(completed)
                    chunksQueue.removeFirst()
                    totalChunksProcessed++
                    
                    AquamixDrawBot.progressTracker.markCompleted(completed)
                    AquamixDrawBot.progressTracker.save()
                    
                    sendMessage("§a[DrawBot] Чанк ${completed.x}, ${completed.z} сломан! Осталось: ${chunksQueue.size}")
                    
                    flightController.sendFlyCommand(client)
                    stateMachine.transition(BotState.FLYING_TO_CHUNK)
                }
            }
            
            BotState.MOVING_TO_NEXT -> {
                stateMachine.transition(BotState.FLYING_TO_CHUNK)
            }
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
            stateMachine.setFlag("confirmed", true)
        }
    }
    
    /**
     * Пропустить текущий чанк
     */
    private fun skipCurrentChunk() {
        if (chunksQueue.isNotEmpty()) {
            val skipped = chunksQueue.removeFirst()
            AquamixDrawBot.LOGGER.warn("Skipped chunk: $skipped")
        }
        stateMachine.transition(BotState.FLYING_TO_CHUNK)
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
    
    // === Геттеры для GUI ===
    
    fun getCompletedChunks(): Set<ChunkPos> = completedChunks.toSet()
    
    fun getQueuedChunks(): List<ChunkPos> = chunksQueue.toList()
    
    fun getCurrentState(): BotState = stateMachine.currentState
    
    fun getTargetChunk(): ChunkPos? = stateMachine.targetChunk
    
    fun getQueueSize(): Int = chunksQueue.size
    
    fun clearCompleted() {
        completedChunks.clear()
        AquamixDrawBot.progressTracker.clear()
    }
}
