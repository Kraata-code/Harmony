package com.dd3boh.outertune.viewmodels

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Engine wrapper for llama.cpp integration with Gemma 3
 * Implements thread-safe LLM operations with official Gemma 3 configuration
 *
 * @property context Android application context
 */
class LlamaEngine(private val context: Context) {

    private companion object {
        const val TAG = "LlamaEngine"
        const val MODEL_FILE_NAME = "gemma-3-270m-it-Q8_0.gguf"

        // Configuración recomendada por el equipo de Gemma 3
        const val MAX_TOKENS_DEFAULT = 128  // Reducido para respuestas más concisas
        const val MAX_TOKENS_LIMIT = 256
    }

    private val llamaBridge = LlamaBridge()
    private val isInitialized = AtomicBoolean(false)

    /**
     * Initialize the LLM model with Gemma 3 configuration
     * Must be called from a coroutine with IO dispatcher
     *
     * @return true if initialization successful, false otherwise
     */
    suspend fun init(): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized.get()) {
            Log.i(TAG, "Model already initialized")
            return@withContext true
        }

        try {
            // Prepare model by copying from assets to app files directory if needed
            Log.i(TAG, "Preparing model from assets...")
            val modelPath = ModelManager.prepareModel(context)
            Log.i(TAG, "Initializing model from: $modelPath")

            val success = llamaBridge.initModel(modelPath)

            if (success) {
                isInitialized.set(true)
                Log.i(TAG, "Model initialized successfully with Gemma 3 config")
            } else {
                Log.e(TAG, "Failed to initialize model via JNI")
            }

            success

        } catch (e: Exception) {
            Log.e(TAG, "Exception during initialization", e)
            isInitialized.set(false)
            false
        }
    }

    /**
     * Generate response from user prompt
     * Uses official Gemma 3 chat format and configuration
     *
     * @param prompt User input text
     * @param maxTokens Maximum tokens to generate (default: 128)
     * @return Generated text response
     */
    suspend fun generateResponse(
        prompt: String,
        maxTokens: Int = MAX_TOKENS_DEFAULT
    ): String = withContext(Dispatchers.IO) {
        if (!isInitialized.get()) {
            Log.e(TAG, "Cannot generate: model not initialized")
            return@withContext "Error: El modelo no está inicializado"
        }

        try {
            Log.d(TAG, "Generating response for: ${prompt.take(50)}...")

            // Formato oficial de Gemma 3
            val formattedPrompt = formatPromptForGemma3(prompt)
            val limitedMaxTokens = maxTokens.coerceIn(1, MAX_TOKENS_LIMIT)

            val response = llamaBridge.generateText(formattedPrompt, limitedMaxTokens)
            val cleanedResponse = cleanResponse(response)

            Log.d(TAG, "Response generated: ${cleanedResponse.take(50)}...")
            cleanedResponse

        } catch (e: Exception) {
            Log.e(TAG, "Exception during generation", e)
            "Error: ${e.message ?: "Error desconocido"}"
        }
    }

    /**
     * Format user prompt for Gemma 3 instruction format
     *
     * Formato oficial según documentación de Gemma 3:
     * <bos><start_of_turn>user\n{message}<end_of_turn>\n<start_of_turn>model\n
     *
     * IMPORTANTE: Los \n son requeridos por el modelo
     *
     * @param userMessage User's message text
     * @return Formatted prompt string
     */
    private fun formatPromptForGemma3(userMessage: String): String {
        // Nota: <bos> se agrega automáticamente con add_bos=true en llama_tokenize
        return buildString {
            append("<start_of_turn>user\n")
            append(userMessage.trim())
            append("<end_of_turn>\n")
            append("<start_of_turn>model\n")
        }
    }

    /**
     * Clean the model's response
     * Removes special tokens and formatting artifacts
     *
     * @param response Raw response from model
     * @return Cleaned response text
     */
    private fun cleanResponse(response: String): String {
        var cleaned = response
            .replace("<start_of_turn>", "")
            .replace("<end_of_turn>", "")
            .replace("<eos>", "")
            .replace("</s>", "")
            .replace("<bos>", "")
            .replace("user\n", "")
            .replace("model\n", "")
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
                words[i + 1] == words[i + 2]) {
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
        } catch (e: Exception) {
            Log.e(TAG, "Exception releasing model", e)
        }
    }
}