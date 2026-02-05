package com.aquamix.drawbot.config

import com.aquamix.drawbot.AquamixDrawBot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
data class ModConfigData(
    val timing: TimingConfig = TimingConfig(),
    val navigation: NavigationConfig = NavigationConfig(),
    val bur: BurConfig = BurConfig(),
    val telegram: TelegramConfig = TelegramConfig(),
    val antiCheat: AntiCheatConfig = AntiCheatConfig()
)

@Serializable
data class TimingConfig(
    var menuClickDelay: Long = 200,        // ms между кликами в меню
    var flyReactivateDelay: Long = 500,    // ms после приземления до /fly
    var actionCooldown: Long = 100,        // ms между действиями
    var chunkBreakWait: Long = 2000        // ms ожидания после клика в меню
)

@Serializable
data class NavigationConfig(
    var flightHeight: Int = 250,           // Y координата для полёта (250 = safe high altitude)
    var landingOffset: Int = 8,            // Смещение к центру чанка (8 = центр)
    var arrivalThreshold: Double = 3.0     // Расстояние для срабатывания "прибыл"
)

@Serializable
data class BurConfig(
    var menuTitle: String = "МЕНЮ БУРА",   // Название меню для определения
    var breakButtonPattern: String = "Незеритов|Сломать все",  // Regex для кнопки
    var confirmationPattern: String = "сломаются все блоки|сломаються все блоки"
)

@Serializable
data class TelegramConfig(
    var enabled: Boolean = false,
    var botToken: String = "",
    var chatId: String = ""
)

@Serializable
data class AntiCheatConfig(
    var enabled: Boolean = true,
    var randomDelayEnabled: Boolean = true,
    var delayVariation: Double = 0.3,      // ±30% variation
    var mouseJitter: Boolean = true,
    var mouseJitterAmount: Float = 0.3f,   // Максимальное отклонение в градусах
    var brandSpoof: Boolean = false        // Отключено по умолчанию (опасно)
)

object ModConfig {
    private val configPath = FabricLoader.getInstance()
        .configDir
        .resolve("${AquamixDrawBot.MOD_ID}.json")
    
    private val json = Json { 
        prettyPrint = true 
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    var data: ModConfigData = ModConfigData()
        private set
    
    fun load() {
        try {
            if (configPath.exists()) {
                val content = configPath.readText()
                data = json.decodeFromString(content)
                AquamixDrawBot.LOGGER.info("Config loaded from $configPath")
            } else {
                save()
                AquamixDrawBot.LOGGER.info("Created default config at $configPath")
            }
        } catch (e: Exception) {
            AquamixDrawBot.LOGGER.error("Failed to load config: ${e.message}")
            data = ModConfigData()
            save()
        }
    }
    
    fun save() {
        try {
            Files.createDirectories(configPath.parent)
            configPath.writeText(json.encodeToString(ModConfigData.serializer(), data))
        } catch (e: Exception) {
            AquamixDrawBot.LOGGER.error("Failed to save config: ${e.message}")
        }
    }
}
