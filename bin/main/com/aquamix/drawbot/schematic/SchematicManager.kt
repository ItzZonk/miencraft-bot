package com.aquamix.drawbot.schematic

import com.aquamix.drawbot.AquamixDrawBot
import com.aquamix.drawbot.automation.ChunkPos
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.io.path.*

/**
 * Схематика - набор чанков для удаления
 */
@Serializable
data class Schematic(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String,
    val createdAt: String = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
    val chunks: List<ChunkPos>,
    val originX: Int = 0,
    val originZ: Int = 0,
    val description: String = ""
) {
    val chunkCount: Int get() = chunks.size
    
    /**
     * Получить границы схематики
     */
    fun getBounds(): Pair<ChunkPos, ChunkPos> {
        if (chunks.isEmpty()) return ChunkPos(0, 0) to ChunkPos(0, 0)
        
        val minX = chunks.minOf { it.x }
        val maxX = chunks.maxOf { it.x }
        val minZ = chunks.minOf { it.z }
        val maxZ = chunks.maxOf { it.z }
        
        return ChunkPos(minX, minZ) to ChunkPos(maxX, maxZ)
    }
    
    /**
     * Сместить все чанки на заданное смещение
     */
    fun offset(dx: Int, dz: Int): Schematic {
        return copy(
            chunks = chunks.map { ChunkPos(it.x + dx, it.z + dz) },
            originX = originX + dx,
            originZ = originZ + dz
        )
    }
}

/**
 * Менеджер схематик - загрузка, сохранение, список
 */
object SchematicManager {
    private val schematicsDir = FabricLoader.getInstance()
        .gameDir
        .resolve("schematics")
    
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    init {
        // Создаём папку если не существует
        if (!schematicsDir.exists()) {
            Files.createDirectories(schematicsDir)
            AquamixDrawBot.LOGGER.info("Created schematics directory: $schematicsDir")
        }
    }
    
    /**
     * Сохранить схематику
     */
    fun save(schematic: Schematic): Boolean {
        return try {
            val file = schematicsDir.resolve("${schematic.id}.json")
            file.writeText(json.encodeToString(Schematic.serializer(), schematic))
            AquamixDrawBot.LOGGER.info("Saved schematic: ${schematic.name} (${schematic.id})")
            true
        } catch (e: Exception) {
            AquamixDrawBot.LOGGER.error("Failed to save schematic: ${e.message}")
            false
        }
    }
    
    /**
     * Загрузить схематику по ID
     */
    fun load(id: String): Schematic? {
        return try {
            val file = schematicsDir.resolve("$id.json")
            if (!file.exists()) return null
            
            json.decodeFromString<Schematic>(file.readText())
        } catch (e: Exception) {
            AquamixDrawBot.LOGGER.error("Failed to load schematic $id: ${e.message}")
            null
        }
    }
    
    /**
     * Получить список всех схематик
     */
    fun list(): List<Schematic> {
        return try {
            schematicsDir.listDirectoryEntries("*.json")
                .mapNotNull { file ->
                    try {
                        json.decodeFromString<Schematic>(file.readText())
                    } catch (e: Exception) {
                        AquamixDrawBot.LOGGER.warn("Invalid schematic file: ${file.name}")
                        null
                    }
                }
                .sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            AquamixDrawBot.LOGGER.error("Failed to list schematics: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Удалить схематику
     */
    fun delete(id: String): Boolean {
        return try {
            val file = schematicsDir.resolve("$id.json")
            if (file.exists()) {
                file.deleteExisting()
                AquamixDrawBot.LOGGER.info("Deleted schematic: $id")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            AquamixDrawBot.LOGGER.error("Failed to delete schematic $id: ${e.message}")
            false
        }
    }
    
    /**
     * Создать схематику из выбранных чанков
     */
    fun createFromSelection(name: String, chunks: Set<ChunkPos>, description: String = ""): Schematic {
        val chunkList = chunks.toList().sortedWith(compareBy({ it.z }, { it.x }))
        
        return Schematic(
            name = name,
            chunks = chunkList,
            description = description
        )
    }
    
    /**
     * Получить путь к папке схематик
     */
    fun getSchematicsPath(): String = schematicsDir.absolutePathString()
}
