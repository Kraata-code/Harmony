/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 * Copyright (C) 2026 Harmony Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.kraata.harmony.viewmodels

import android.content.Context
import android.util.Log
import com.kraata.harmony.db.MusicDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Engine wrapper for llama.cpp integration with Qwen2.
 *
 * The native layer now owns the persistent ChatML session so Kotlin sends plain user text turns.
 */
class LlamaEngine(private val context: Context, private val database: MusicDatabase) {

    companion object {
        const val TAG = "LlamaEngine"
        const val MODEL_FILE_NAME = "Qwen2-500M-Instruct-IQ4_XS.gguf"

        const val MAX_TOKENS_DEFAULT = 128
        const val MAX_TOKENS_LIMIT = 256
        const val CONTEXT_LENGTH_TOKENS = 4096
    }

    private val llamaBridge = LlamaBridge()
    private val isInitialized = AtomicBoolean(false)
    private val runtimeInitialized = AtomicBoolean(false)

    private val tools by lazy { AIToolsViewModel(database) }

    data class ToolCall(
        val name: String,
        val arguments: Map<String, String>
    )

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
            if (!runtimeInitialized.get()) {
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                require(nativeLibDir.isNotBlank()) { "Directorio de librerías nativas inválido" }

                Log.i(TAG, "Initializing llama.cpp runtime from: $nativeLibDir")
                llamaBridge.initRuntime(nativeLibDir)
                runtimeInitialized.set(true)
            }

            Log.i(TAG, "Preparing model from assets...")
            val modelPath = ModelManager.prepareModel(context)
            Log.i(TAG, "Initializing Qwen2 model from: $modelPath")

            val success = llamaBridge.initModel(modelPath, CONTEXT_LENGTH_TOKENS)

            if (success) {
                isInitialized.set(true)
                Log.i(TAG, "Qwen2 model initialized successfully")
            } else {
                Log.e(TAG, "Failed to initialize model via JNI")
                if (runtimeInitialized.getAndSet(false)) {
                    llamaBridge.releaseModel()
                }
            }

            success
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "JNI library unavailable during initialization", e)
            isInitialized.set(false)
            runtimeInitialized.set(false)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Exception during initialization", e)
            isInitialized.set(false)
            if (runtimeInitialized.getAndSet(false)) {
                runCatching { llamaBridge.releaseModel() }
            }
            false
        }
    }

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

            val limitedMaxTokens = maxTokens.coerceIn(1, MAX_TOKENS_LIMIT)
            val response = llamaBridge.generateText(prompt.trim(), limitedMaxTokens)

            if (tools.isToolCall(response)) {
                val toolCall = tools.parseToolCall(response)
                    ?: return@withContext "Error procesando la herramienta"

                Log.i(TAG, "Tool name: ${toolCall.name}")
                Log.i(TAG, "Tool arguments: ${toolCall.arguments}")

                when (toolCall.name) {
                    "get_date_info" -> {
                        val result = tools.getDateInfo()
                        val finalPrompt = buildToolResultFollowUpPrompt(prompt, result)
                        return@withContext cleanResponse(
                            llamaBridge.generateText(finalPrompt, limitedMaxTokens)
                        )
                    }

                    "get_internet_info" -> {
                        val query = toolCall.arguments["query"] ?: ""
                        val result = tools.getInternetInfo(query)
                        Log.i(TAG, "resultado de internet $result")
                        val finalPrompt = buildToolResultFollowUpPrompt(prompt, result)
                        return@withContext cleanResponse(
                            llamaBridge.generateText(finalPrompt, limitedMaxTokens)
                        )
                    }

                    "create_playlist" -> {
                        val rawName =
                            toolCall.arguments["name"] ?: toolCall.arguments["playlistName"] ?: ""
                        val playlistName = rawName.trim()

                        return@withContext try {
                            tools.createPlaylist(playlistName)
                            "Playlist creada: \"$playlistName\""
                        } catch (e: Exception) {
                            Log.e(TAG, "Error al crear playlist", e)
                            "Error al crear la playlist: ${e.message ?: "Desconocido"}"
                        }
                    }

                    "insert_artis_playlist" -> {
                        val rawArtist = toolCall.arguments["artist"] ?: ""
                        val rawPlaylist = toolCall.arguments["playlistName"] ?: ""
                        val result = tools.insertPlaylist(rawArtist, rawPlaylist)
                        return@withContext "$result ¿Deseas algo más?"
                    }

                    "find_local_songs" -> {
                        val query = toolCall.arguments["query"]?.trim().orEmpty()
                        val limit = toolCall.arguments["limit"]?.toIntOrNull() ?: 5
                        val safeLimit = limit.coerceIn(1, 20)

                        val result = tools.findLocalSongs(query, safeLimit)
                        val finalPrompt = buildToolResultFollowUpPrompt(prompt, result)
                        return@withContext cleanResponse(
                            llamaBridge.generateText(finalPrompt, limitedMaxTokens)
                        )
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

    private fun buildToolResultFollowUpPrompt(
        originalUserPrompt: String,
        toolResult: String,
    ): String = buildString {
        append("Ya ejecutaste una herramienta para ayudar con la solicitud anterior.\n\n")
        append("Solicitud original del usuario:\n")
        append(originalUserPrompt.trim())
        append("\n\n")
        append("Resultado de la herramienta:\n")
        append(toolResult.trim())
        append("\n\n")
        append("Ahora responde de forma natural en espanol.\n")
        append("No devuelvas JSON.\n")
        append("No llames mas herramientas.\n")
        append("Da solo la respuesta final para el usuario.\n")
    }

    private fun cleanResponse(response: String): String {
        var cleaned = response
            .replace("<|im_start|>", "")
            .replace("<|im_end|>", "")
            .replace("<|endoftext|>", "")
            .replace("system\n", "")
            .replace("user\n", "")
            .replace("assistant\n", "")
            .trim()

        cleaned = cleaned.replace(Regex("\n{3,}"), "\n\n")

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

    private fun hasRepetitivePattern(text: String): Boolean {
        if (text.length < 20) return false

        val words = text.split(Regex("\\s+"))
        if (words.size < 6) return false

        for (i in 0 until words.size - 2) {
            val word = words[i]
            if (word.length < 3) continue

            if (words[i] == words[i + 1] && words[i + 1] == words[i + 2]) {
                return true
            }
        }

        return false
    }

    fun isInitialized(): Boolean = isInitialized.get()

    fun clearConversation() {
        try {
            if (runtimeInitialized.get()) {
                llamaBridge.clearConversation()
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "JNI library unavailable while clearing conversation", e)
        } catch (e: Exception) {
            Log.e(TAG, "Exception clearing conversation", e)
        }
    }

    fun release() {
        try {
            val hadModel = isInitialized.getAndSet(false)
            val hadRuntime = runtimeInitialized.getAndSet(false)

            if (hadModel || hadRuntime) {
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
