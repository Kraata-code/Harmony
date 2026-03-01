package com.kraata.harmony.viewmodels

import android.content.Context
import android.util.Log
import com.kraata.harmony.db.MusicDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Engine wrapper for llama.cpp integration with Qwen2
 * Implements thread-safe LLM operations with ChatML format
 *
 * @property context Android application context
 */
class LlamaEngine(private val context: Context, private val database: MusicDatabase) {

    companion object {
        const val TAG = "LlamaEngine"
        const val MODEL_FILE_NAME = "Qwen2-500M-Instruct-IQ4_XS.gguf"

        // Parámetros optimizados para Qwen2
        const val MAX_TOKENS_DEFAULT = 128
        const val MAX_TOKENS_LIMIT = 256
        const val CONTEXT_LENGTH_TOKENS = 4096
    }

    private val llamaBridge = LlamaBridge()
    private val isInitialized = AtomicBoolean(false)

    private val tools by lazy { AIToolsViewModel(database) }

    data class ToolCall(
        val name: String,
        val arguments: Map<String, String>
    )

    /**
     * Initialize the LLM model with Qwen2 configuration
     * Must be called from a coroutine with IO dispatcher
     *
     * @return true if initialization successful, false otherwise
     */
    suspend fun init(): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized.get()) {
            Log.i(TAG, "Model already initialized")
            return@withContext true
        }

        if (!LlamaBridge.isNativeLibraryLoaded()) {
            Log.e(TAG, "llama-jni-lib is not available for this ABI/device")
            isInitialized.set(false)
            return@withContext false
        }

        try {
            Log.i(TAG, "Preparing model from assets...")
            val modelPath = ModelManager.prepareModel(context)
            Log.i(TAG, "Initializing Qwen2 model from: $modelPath")

            val success = llamaBridge.initModel(modelPath, CONTEXT_LENGTH_TOKENS)

            if (success) {
                isInitialized.set(true)
                Log.i(TAG, "Qwen2 model initialized successfully")
            } else {
                Log.e(TAG, "Failed to initialize model via JNI")
            }

            success

        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "JNI library unavailable during initialization", e)
            isInitialized.set(false)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Exception during initialization", e)
            isInitialized.set(false)
            false
        }
    }

    /**
     * Generate response from user prompt
     * Uses ChatML format (Qwen2's official format)
     *
     * @param prompt User input text
     * @param maxTokens Maximum tokens to generate (default: 128)
     * @param allowedTools User input text
     * @return Generated text response
     */
    suspend fun generateResponse(
        prompt: String,
        maxTokens: Int = MAX_TOKENS_DEFAULT,
    ): String = withContext(Dispatchers.IO) {
        if (!isInitialized.get()) {
            Log.e(TAG, "Cannot generate: model not initialized")
            return@withContext "Error: El modelo no está inicializado"
        }

        try {
            Log.d(TAG, "Generating response for: ${prompt.take(50)}...")

            // Formato ChatML oficial de Qwen2
            val formattedPrompt = formatPromptForQwen2(prompt)
            val limitedMaxTokens = maxTokens.coerceIn(1, MAX_TOKENS_LIMIT)

            val response = llamaBridge.generateText(formattedPrompt, limitedMaxTokens)
            if (tools.isToolCall(response)) {
                val toolCall = tools.parseToolCall(response)
                if (toolCall == null) {
                    return@withContext "Error procesando la herramienta"
                }
                Log.i(TAG, "Tool name: ${toolCall.name}")
                Log.i(TAG, "Tool arguments: ${toolCall.arguments}")
                when (toolCall.name) {
                    "get_date_info" -> {
                        val result = tools.getDateInfo()
                        val secondFormattedPrompt = formatFinalAnswerPrompt(prompt, result)
                        val finalResponse = llamaBridge.generateText(
                            secondFormattedPrompt,
                            limitedMaxTokens
                        )
                        return@withContext cleanResponse(finalResponse)
                    }

                    "get_internet_info" -> {
                        Log.i(TAG, "Usando informacion de internet")
                        val query = toolCall.arguments["query"] ?: ""
                        val result = tools.getInternetInfo(query)
                        Log.i(TAG, "resultado de internet $result")
                        val secondFormattedPrompt = formatFinalAnswerPrompt(prompt, result)
                        val finalResponse = llamaBridge.generateText(
                            secondFormattedPrompt,
                            limitedMaxTokens
                        )
                        return@withContext cleanResponse(finalResponse)
                    }

                    "create_playlist" -> {
                        Log.i(TAG, "Entrando a funcion de playlist")
                        val rawName =
                            toolCall.arguments["name"] ?: toolCall.arguments["playlistName"] ?: ""
                        val playlistName = rawName.trim()

                        try {
                            tools.createPlaylist(playlistName)
                            val result = "Playlist creada: \"$playlistName\""
                            Log.i(TAG, "antes de format Promt$result")
                            return@withContext result
                        } catch (e: Exception) {
                            Log.e(TAG, "Error al crear playlist", e)
                            val errorResult =
                                "Error al crear la playlist: ${e.message ?: "Desconocido"}"
                            return@withContext errorResult
                        }
                    }

                    "insert_artis_playlist" -> {
                        Log.i(TAG, "Entrando en funcion para insertar canciones")
                        val rawArtis =
                            toolCall.arguments["artist"] ?: ""
                        val rawPlaylist =
                            toolCall.arguments["playlistName"] ?: ""
                        val result = tools.insertPlaylist(rawArtis, rawPlaylist)
                        return@withContext "$result ¿Deseas algo más?"
                    }

                    "find_local_songs" -> {
                        val query = toolCall.arguments["query"]?.trim().orEmpty()
                        val limit = toolCall.arguments["limit"]?.toIntOrNull() ?: 5
                        val safeLimit = limit.coerceIn(1, 20)

                        val result = tools.findLocalSongs(query, safeLimit)
                        val secondFormattedPrompt = formatFinalAnswerPrompt(prompt, result)
                        val finalResponse = llamaBridge.generateText(
                            secondFormattedPrompt,
                            limitedMaxTokens
                        )
                        return@withContext cleanResponse(finalResponse)
                    }

                    else -> {
                    }
                }
            }

            val cleanedResponse = cleanResponse(response)

            Log.d(TAG, "Response generated: ${cleanedResponse.take(50)}...")
            cleanedResponse

        } catch (e: UnsatisfiedLinkError) {
            "Error: Biblioteca nativa no disponible para este dispositivo"
        } catch (e: Exception) {
            "Error: ${e.message ?: "Error desconocido"}"
        }
    }

    /**
     * Format user prompt for Qwen2 ChatML format
     *
     * Formato oficial ChatML de Qwen2:
     * <|im_start|>system
     * {system_message}<|im_end|>
     * <|im_start|>user
     * {user_message}<|im_end|>
     * <|im_start|>assistant
     *
     * Referencias:
     * - https://qwen.readthedocs.io/en/latest/getting_started/concepts.html
     * - https://huggingface.co/Qwen/Qwen2-7B-Instruct
     *prompt funcional playlist agrega las canciones de The Doors a la playlist llamada Door for ever
     * @param userMessage User's message text
     * @return Formatted prompt string in ChatML format
     */
    private fun formatPromptForQwen2(userMessage: String): String {
        // ChatML format con system message opcional
        return buildString {
            // System message (opcional)
            append("<|im_start|>system\n")
            append(
                "You are a helpful assistant with access to these tools:\n" +
                        "\n" +
                        "1. get_date_info() - Use ONLY for current date/time queries\n" +
                        "\n" +
                        "2. get_internet_info(query: string)\n" +
                        "   - MANDATORY for:\n" +
                        "     * ANY question about real people, bands, artists, companies, places\n" +
                        "     * ANY question with 'search', 'look up', 'find', 'who is', 'what is'\n" +
                        "     * Current events, presidents, officials, celebrities\n" +
                        "     * Music: songs, albums, discographies, band members\n" +
                        "     * Definitions, facts, statistics, historical data\n" +
                        "   - Use short keyword queries (e.g., 'Los Tigres del Norte band', 'current Mexico president')\n" +
                        "   - NO URLs in queries\n" +
                        "   - When user says 'search internet' or 'look up', ALWAYS use this tool\n" +
                        "\n" +
                        "3. create_playlist(name: string) - Use when user requests playlist creation\n" +
                        "\n" +
                        "4. insert_artis_playlist(artist: String, playlistName: String)\n" +
                        "   - Use when adding artist songs to a playlist\n" +
                        "   - Extract BOTH parameters from user request:\n" +
                        "     * artist: Exact artist name\n" +
                        "     * playlistName: Exact playlist name as user states it\n" +
                        "   - Example: 'add The Doors to playlist Doors for ever' →\n" +
                        "     {\"tool\": \"insert_artis_playlist\", \"arguments\": {\"artist\": \"The Doors\", \"playlistName\": \"Doors for ever\"}}\n" +
                        "\n" +
                        "5. find_local_songs(query: string, limit: string?)\n" +
                        "   - Use when user asks to find songs in their local library\n" +
                        "   - Query should be plain text, no URLs\n" +
                        "   - limit is optional and should be a number as string\n" +
                        "   - Example:\n" +
                        "     {\"tool\": \"find_local_songs\", \"arguments\": {\"query\": \"soda\", \"limit\": \"5\"}}\n" +
                        "\n" +
                        "RESPONSE FORMAT:\n" +
                        "For tool calls, respond ONLY with JSON (no extra text):\n" +
                        "{\n" +
                        "  \"tool\": \"<tool_name>\",\n" +
                        "  \"arguments\": {\"<param>\": \"<value>\"}\n" +
                        "}\n"
            )
            append("<|im_end|>\n")

            // User message
            append("<|im_start|>user\n")
            append(userMessage.trim())
            append("<|im_end|>\n")

            // Assistant turn (sin contenido, para que el modelo genere)
            append("<|im_start|>assistant\n")
        }
    }

    /**
     * Formatea el prompt para que el modelo genere una respuesta final
     * después de haber ejecutado una herramienta.
     *
     * Muestra al modelo el contexto completo:
     * 1. La pregunta original del usuario
     * 2. El tool call que ya ejecutó
     * 3. El resultado de la herramienta
     * 4. Que ahora debe responder en lenguaje natural
     *
     * @param originalUserPrompt La pregunta original del usuario
     * @param toolResult El resultado obtenido de ejecutar la herramienta
     * @return Prompt formateado en ChatML listo para generar respuesta final
     */
    private fun formatFinalAnswerPrompt(originalUserPrompt: String, toolResult: String): String {
        return buildString {
            append("<|im_start|>system\n")
            append(
                "You are a helpful assistant. You have just received information from a tool.\n" +
                        "IMPORTANT: Do NOT output any JSON, tool call, or machine-readable object. Answer ONLY in plain natural language (Spanish is preferred).\n" +
                        "If the tool result says the playlist was created, respond with a friendly confirmation sentence. Example response:\n" +
                        "\"He creado la lista de reproducción 'beatles' correctamente. ¿Deseas que añada canciones ahora?\"\n" +
                        "Now provide a direct, natural-language answer to the user's original question using the tool result. Do NOT call any more tools.\n"
            )
            append("<|im_end|>\n")

            append("<|im_start|>user\n")
            append(originalUserPrompt.trim())
            append("<|im_end|>\n")

            append("<|im_start|>tool\n")
            append(toolResult.trim())
            append("<|im_end|>\n")

            append("<|im_start|>assistant\n")
        }
    }

    /**
     * Clean the model's response
     * Removes ChatML special tokens and formatting artifacts
     *
     * @param response Raw response from model
     * @return Cleaned response text
     */
    private fun cleanResponse(response: String): String {
        var cleaned = response
            // Remover tokens especiales de ChatML
            .replace("<|im_start|>", "")
            .replace("<|im_end|>", "")
            .replace("<|endoftext|>", "")
            .replace("system\n", "")
            .replace("user\n", "")
            .replace("assistant\n", "")
            .trim()

        // Remover líneas vacías múltiples
        cleaned = cleaned.replace(Regex("\n{3,}"), "\n\n")

        // Verificar si hay patrones repetitivos (indica fallo de generación)
        if (hasRepetitivePattern(cleaned)) {
            Log.w(TAG, "Detected repetitive pattern in response")
            return "El modelo generó una respuesta inválida. Por favor, intenta de nuevo."
        }

        return if (cleaned.isEmpty()) {
            "El modelo no generó una respuesta válida."
        } else {
            cleaned
        }
    }

    /**
     * Check if text has repetitive patterns (sign of generation failure)
     *
     * @param text Input text to check
     * @return true if repetitive pattern detected, false otherwise
     */
    private fun hasRepetitivePattern(text: String): Boolean {
        if (text.length < 20) return false

        // Buscar palabras que se repiten 3+ veces seguidas
        val words = text.split(Regex("\\s+"))
        if (words.size < 6) return false

        for (i in 0 until words.size - 2) {
            val word = words[i]
            if (word.length < 3) continue  // Ignorar palabras muy cortas

            // Verificar si la misma palabra aparece 3+ veces consecutivas
            if (i + 2 < words.size &&
                words[i] == words[i + 1] &&
                words[i + 1] == words[i + 2]
            ) {
                return true
            }
        }

        return false
    }

    /**
     * Check if engine is initialized and ready
     *
     * @return true if initialized, false otherwise
     */
    fun isInitialized(): Boolean = isInitialized.get()

    /**
     * Release model resources
     * Should be called when engine is no longer needed
     */
    fun release() {
        try {
            if (isInitialized.compareAndSet(true, false)) {
                llamaBridge.releaseModel()
                Log.i(TAG, "Model released successfully")
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "JNI library unavailable while releasing model", e)
        } catch (e: Exception) {
            Log.e(TAG, "Exception releasing model", e)
        }
    }
}
