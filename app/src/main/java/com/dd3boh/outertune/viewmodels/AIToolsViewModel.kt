package com.dd3boh.outertune.viewmodels

import android.util.Log
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.service.DuckDuckGoService
import com.dd3boh.outertune.viewmodels.LlamaEngine.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
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

    suspend fun insertPlaylist(artist: String, playlistName: String): String =
        withContext(Dispatchers.IO) {
            try {
                val normalizedArtist = artist.trim()
                val normalizedPlaylistName = playlistName.trim().ifBlank { normalizedArtist }

                Log.i(
                    TAG,
                    "Procesando: artista='$normalizedArtist', playlist='$normalizedPlaylistName'"
                )

                // PASO 1: Búsqueda exacta (rápida y precisa)
                val exactSongs = database.songsByArtistNameExact(normalizedArtist)
                Log.d(TAG, "Búsqueda exacta: ${exactSongs.size} canciones")

                // PASO 2: Búsqueda parcial (completa, encuentra variaciones)
                val partialSongs = database.songsByArtistNamePartial(normalizedArtist)
                Log.d(TAG, "Búsqueda parcial: ${partialSongs.size} canciones")

                // PASO 3: Merge inteligente - combinar sin duplicados
                val allSongs = mergeSongs(exactSongs, partialSongs)
                Log.d(TAG, "Total después de merge: ${allSongs.size} canciones únicas")

                if (allSongs.isEmpty()) {
                    return@withContext "No se encontraron canciones locales de '$normalizedArtist'"
                }

                // Log detallado solo si hay diferencia significativa
                val difference = allSongs.size - exactSongs.size
                if (difference > 0) {
                    Log.i(TAG, "Búsqueda parcial agregó $difference canción(es) adicional(es)")
                    // Log de las canciones adicionales encontradas
                    val exactIds = exactSongs.map { it.id }.toSet()
                    allSongs.filter { !exactIds.contains(it.id) }.forEach { song ->
                        Log.d(TAG, "  + ${song.song.title}")
                    }
                }

                // Resto del proceso (crear/buscar playlist, agregar canciones)
                val existingPlaylist = findPlaylistByName(normalizedPlaylistName)
                val playlistId = if (existingPlaylist != null) {
                    Log.d(
                        TAG,
                        "Playlist '${existingPlaylist.name}' ya existe con ID: ${existingPlaylist.id}"
                    )
                    existingPlaylist.id
                } else {
                    Log.d(TAG, "Creando nueva playlist: '$normalizedPlaylistName'")
                    val newPlaylist = PlaylistEntity(
                        name = normalizedPlaylistName,
                        browseId = null,
                        bookmarkedAt = LocalDateTime.now(),
                        isEditable = true,
                        isLocal = true
                    )
                    database.insert(newPlaylist)
                    newPlaylist.id
                }

                val songIds = allSongs.map { it.id }
                val duplicates = database.playlistDuplicates(playlistId, songIds)
                val songsToAdd = songIds.filter { !duplicates.contains(it) }

                if (songsToAdd.isEmpty()) {
                    return@withContext "Todas las canciones de '$normalizedArtist' ya están en '$normalizedPlaylistName'"
                }

                val playlist = database.playlist(playlistId).firstOrNull()
                    ?: throw IllegalStateException("No se pudo encontrar la playlist con ID: $playlistId")

                database.addSongToPlaylist(playlist, songsToAdd)

                val addedCount = songsToAdd.size
                val skippedCount = duplicates.size
                val playlistStatus = if (existingPlaylist != null) "existente" else "nueva"

                return@withContext buildString {
                    append("$addedCount canción${if (addedCount != 1) "es" else ""} de '$normalizedArtist' ")
                    append("agregada${if (addedCount != 1) "s" else ""} a la playlist $playlistStatus '$normalizedPlaylistName'")
                    if (skippedCount > 0) {
                        append("\n($skippedCount duplicado${if (skippedCount != 1) "s" else ""} omitido${if (skippedCount != 1) "s" else ""})")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error al procesar playlist para artista '$artist'", e)
                throw e
            }
        }

    /**
     * Combina dos listas de canciones eliminando duplicados
     * Usa LinkedHashSet para mantener el orden (exactas primero) y eliminar duplicados
     *
     * @param exactSongs Canciones de búsqueda exacta (tienen prioridad)
     * @param partialSongs Canciones de búsqueda parcial
     * @return Lista combinada sin duplicados
     */
    private fun mergeSongs(exactSongs: List<Song>, partialSongs: List<Song>): List<Song> {
        // LinkedHashSet mantiene el orden de inserción
        val uniqueSongs = LinkedHashSet<Song>()

        // Primero agregar las exactas (tienen prioridad)
        uniqueSongs.addAll(exactSongs)

        // Luego agregar las parciales (solo si no están ya)
        uniqueSongs.addAll(partialSongs)

        return uniqueSongs.toList()
    }

    /**
     * Busca una playlist por nombre exacto (case-insensitive y trimmed)
     *
     * @param playlistName Nombre de la playlist a buscar
     * @return PlaylistEntity si existe, null en caso contrario
     */
    private suspend fun findPlaylistByName(playlistName: String): PlaylistEntity? {
        return try {
            val normalized = playlistName.trim()
            database.playlistByName(normalized).firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Playlist '$playlistName' no encontrada o error al buscar", e)
            null
        }
    }
}