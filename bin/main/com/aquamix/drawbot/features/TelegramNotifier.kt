package com.aquamix.drawbot.features

import com.aquamix.drawbot.AquamixDrawBot
import com.aquamix.drawbot.config.ModConfig
import kotlinx.coroutines.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

object TelegramNotifier {
    
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
        
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    fun sendNotification(message: String) {
        val config = ModConfig.data.telegram
        
        if (!config.enabled || config.botToken.isBlank() || config.chatId.isBlank()) {
            return
        }
        
        scope.launch {
            try {
                // Экранируем специальные символы для MarkdownV2, если нужно, или шлем как текст
                // Для простоты пока шлем plain text, но URL-encoded
                
                val url = "https://api.telegram.org/bot${config.botToken}/sendMessage"
                
                // Простая JSON строка
                val jsonBody = """
                    {
                        "chat_id": "${config.chatId}",
                        "text": "${escapeJson(message)}"
                    }
                """.trimIndent()
                
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build()
                    
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                
                if (response.statusCode() != 200) {
                    AquamixDrawBot.LOGGER.warn("Failed to send Telegram message: ${response.statusCode()} - ${response.body()}")
                }
            } catch (e: Exception) {
                AquamixDrawBot.LOGGER.error("Error sending Telegram notification: ${e.message}")
            }
        }
    }
    
    private fun escapeJson(text: String): String {
        return text.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
