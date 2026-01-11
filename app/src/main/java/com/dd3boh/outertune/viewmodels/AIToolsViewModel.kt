package com.dd3boh.outertune.viewmodels

import android.util.Log
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.service.DuckDuckGoService
import com.dd3boh.outertune.viewmodels.LlamaEngine.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

class AIToolsViewModel(private val database: MusicDatabase) {
    private val googleScrapingService = DuckDuckGoService()

    companion object {
        private const val TAG = "AIToolsViewModel"
        private const val MAX_ANSWER_LENGTH = 500
    }

    fun isToolCall(response: String): Boolean {
        val cleaned = response
            .replace("<|im_start|>", "")
            .replace("<|im_end|>", "")
            .replace("assistant", "")
            .trim()
        return cleaned.contains("\"tool\"") && cleaned.contains("\"arguments\"")
    }

    fun parseToolCall(response: String): ToolCall? {
        return try {
            val cleaned = response
                .replace("<|im_start|>", "")
                .replace("<|im_end|>", "")
                .replace("assistant", "")
                .trim()

            val json = org.json.JSONObject(cleaned)
            val toolName = json.getString("tool")
            val argsObject = json.getJSONObject("arguments")

            val args = mutableMapOf<String, String>()
            argsObject.keys().forEach { key ->
                args[key] = argsObject.getString(key)
            }

            ToolCall(toolName, args)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse tool call", e)
            null
        }
    }

    fun getDateInfo(): String {
        val today = java.time.LocalDate.now()
        return "Internet OK – fecha: $today"
    }

    suspend fun getInternetInfo(query: String): String = withContext(Dispatchers.IO) {
        try {
            // usa la instancia de clase en lugar de crear una nueva
            val cleanQuery = cleanQuery(query)
            Log.d(TAG, "Buscando información para: $cleanQuery")

            val rawAnswer = googleScrapingService.searchQuickAnswer(cleanQuery)
            Log.d(TAG, "Respuesta cruda obtenida (preview): ${rawAnswer.take(200)}")

            val formatted = formatGoogleAnswer(rawAnswer, cleanQuery)

            // debug: mostrar exactamente lo que devolverás
            Log.d(TAG, "Contenido final devuelto por getInternetInfo: ${formatted.take(400)}")

            return@withContext formatted
        } catch (e: Exception) {
            Log.e(TAG, "Error crítico en getInternetInfo", e)
            "Lo siento, no pude encontrar información sobre \"$query\". Intenta con una búsqueda más específica."
        }
    }

    suspend fun createPlaylist(playlistName: String) = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Creando playlist: $playlistName")
            database.query {
                insert(
                    PlaylistEntity(
                        name = playlistName,
                        browseId = null,
                        bookmarkedAt = LocalDateTime.now(),
                        isEditable = true,
                        isLocal = true
                    )
                )
            }
            return@withContext "'$playlistName' creada exitosamente"
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error al crear playlist '$playlistName': ${e.message}", e)
            throw e
        }
    }

    private fun cleanQuery(query: String): String {
        return if (query.contains(".com") || query.contains(".org") || query.contains("http")) {
            query
                .replace(Regex("https?://"), "")
                .replace("www.", "")
                .replace(Regex("\\.(com|org|net|tv|io|co|info)"), " ")
                .replace("/", " ")
                .replace("-", " ")
                .replace("_", " ")
                .split(Regex("\\s+"))
                .filter { it.length > 2 && !it.equals("the", ignoreCase = true) }
                .take(7) // Tomar un poco más de términos
                .joinToString(" ")
        } else {
            // Para queries normales, asegurar que no excedan cierta longitud
            query.take(100)
        }
    }

    /**
     * Formatea la respuesta de Google para mejor presentación
     */

    private fun formatGoogleAnswer(rawAnswer: String, originalQuery: String): String {
        // limpia y normaliza
        val formatted = formatAnswerText(rawAnswer)

        // trunca si es necesario
        val truncated = if (formatted.length > MAX_ANSWER_LENGTH) {
            formatted.substring(0, MAX_ANSWER_LENGTH) + "..."
        } else {
            formatted
        }

        // intenta extraer una URL si existe en el rawAnswer
        val urlRegex = Regex("(https?://[^\\s]+)")
        val urlMatch = urlRegex.find(rawAnswer)

        // intenta extraer una línea tipo "Fuente: ..." si existe
        val sourceRegex = Regex("Fuente[:\\-]\\s*(.+)", RegexOption.IGNORE_CASE)
        val sourceMatch = sourceRegex.find(rawAnswer)

        return buildString {
            append("Información sobre: $originalQuery\n\n")
            append(truncated)

            if (urlMatch != null) {
                append("\n\nFuente: ${urlMatch.value}")
            } else if (sourceMatch != null) {
                append("\n\nFuente: ${sourceMatch.groupValues[1].trim()}")
            }
        }
    }

    private fun formatAnswerText(text: String): String {
        return text
            .replace(Regex("\\s+"), " ")
            .replace(Regex("(?<=[a-záéíóúñ])\\.(?=[A-ZÁÉÍÓÚÑ])"), ". ")
            .trim()
    }

}