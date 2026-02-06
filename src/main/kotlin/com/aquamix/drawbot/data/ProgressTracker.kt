package com.aquamix.drawbot.data

import com.aquamix.drawbot.AquamixDrawBot
import com.aquamix.drawbot.automation.ChunkPos
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Данные о прогрессе выполнения
 */
@Serializable
data class ProgressData(
    val completedChunks: MutableSet<ChunkPos> = mutableSetOf(),
    var currentSchematicId: String? = null,
    var totalProcessed: Int = 0,
    val queuedChunks: MutableList<ChunkPos> = mutableListOf()
)

/**
 * Трекер прогресса для сохранения между сессиями
 */
class ProgressTracker {
    private val progressPath = FabricLoader.getInstance()
        .configDir
        .resolve("${AquamixDrawBot.MOD_ID}-progress.json")
    
    private val json = Json { 
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    
    private var data = ProgressData()
    
    /**
     * Загрузить прогресс из файла
     */
    fun load() {
        try {
            if (progressPath.exists()) {
                val content = progressPath.readText()
                data = json.decodeFromString(content)
                AquamixDrawBot.LOGGER.info("Loaded progress: ${data.completedChunks.size} completed, ${data.queuedChunks.size} queued")
            }
        } catch (e: Exception) {
            AquamixDrawBot.LOGGER.error("Failed to load progress: ${e.message}")
            data = ProgressData()
        }
    }
    
    /**
     * Сохранить прогресс в файл
     */
    fun save() {
        try {
            // Serialize on main thread to ensure consistency
            val jsonString = json.encodeToString(ProgressData.serializer(), data)
            
            // Write to disk asynchronously (Fire & Forget)
            java.util.concurrent.CompletableFuture.runAsync {
                try {
                    Files.createDirectories(progressPath.parent)
                    progressPath.writeText(jsonString)
                } catch (e: Exception) {
                    AquamixDrawBot.LOGGER.error("Failed to save progress async: ${e.message}")
                }
            }
        } catch (e: Exception) {
            AquamixDrawBot.LOGGER.error("Failed to serialize progress: ${e.message}")
        }
    }
    
    /**
     * Отметить чанк как выполненный
     */
    fun markCompleted(chunk: ChunkPos) {
        data.completedChunks.add(chunk)
        data.totalProcessed++
    }
    
    /**
     * Отметить группу чанков как выполненные
     */
    fun markBatchCompleted(chunks: Collection<ChunkPos>) {
        val newChunks = chunks.filter { data.completedChunks.add(it) }
        data.totalProcessed += newChunks.size
    }
    
    /**
     * Проверить, выполнен ли чанк
     */
    fun isCompleted(chunk: ChunkPos): Boolean {
        return chunk in data.completedChunks
    }
    
    /**
     * Получить все выполненные чанки
     */
    fun getCompletedChunks(): Set<ChunkPos> = data.completedChunks.toSet()
    
    /**
     * Получить очередь чанков
     */
    fun getQueuedChunks(): List<ChunkPos> = data.queuedChunks.toList()
    
    /**
     * Сохранить текущую очередь
     */
    fun setQueuedChunks(chunks: List<ChunkPos>) {
        data.queuedChunks.clear()
        data.queuedChunks.addAll(chunks)
    }
    
    /**
     * Получить общее количество обработанных чанков
     */
    fun getTotalProcessed(): Int = data.totalProcessed
    
    /**
     * Установить текущую схематику
     */
    fun setCurrentSchematic(id: String?) {
        data.currentSchematicId = id
    }
    
    /**
     * Получить ID текущей схематики
     */
    fun getCurrentSchematicId(): String? = data.currentSchematicId
    
    /**
     * Очистить весь прогресс
     */
    fun clear() {
        data = ProgressData()
        save()
    }
    
    /**
     * Очистить только выполненные чанки (сохранить статистику)
     */
    fun clearCompleted() {
        data.completedChunks.clear()
        save()
    }
}
