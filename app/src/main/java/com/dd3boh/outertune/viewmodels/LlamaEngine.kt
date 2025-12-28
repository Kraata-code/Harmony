package com.dd3boh.outertune.viewmodels

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Engine wrapper for llama.cpp integration
 * Provides thread-safe initialization and text generation
 */
class LlamaEngine(private val context: Context) {

    private val TAG = "LlamaEngine"
    private val llamaBridge = LlamaBridge()

    // Thread-safe initialization flag
    private val _isInitialized = AtomicBoolean(false)

    // Model configuration
    private val modelFileName = "gemma-3-270m-it-Q8_0.gguf"

    /**
     * Initialize the model
     * Must be called from a background thread (IO dispatcher)
     * @return true if initialization successful
     */
    suspend fun init(): Boolean = withContext(Dispatchers.IO) {
        // Evitar reinicialización
        if (_isInitialized.get()) {
            Log.i(TAG, "Model already initialized")
            return@withContext true
        }

        try {
            val modelFile = File(context.filesDir, modelFileName)

            if (!modelFile.exists()) {
                Log.e(TAG, "Model file not found: ${modelFile.absolutePath}")
                return@withContext false
            }

            val modelPath = modelFile.absolutePath
            Log.i(TAG, "Initializing model from: $modelPath")

            // Llamar a JNI para inicializar
            val success = llamaBridge.initModel(modelPath)

            if (success) {
                // Actualizar flag de forma atómica
                _isInitialized.set(true)
                Log.i(TAG, "Model initialized successfully")
            } else {
                Log.e(TAG, "Failed to initialize model via JNI")
            }

            return@withContext success

        } catch (e: Exception) {
            Log.e(TAG, "Exception during initialization", e)
            _isInitialized.set(false)
            return@withContext false
        }
    }

    /**
     * Generate response from prompt
     * @param prompt User input text
     * @param maxTokens Maximum tokens to generate
     * @return Generated text response
     */
    suspend fun generateResponse(
        prompt: String,
        maxTokens: Int = 256
    ): String = withContext(Dispatchers.IO) {
        if (!_isInitialized.get()) {
            Log.e(TAG, "Cannot generate: model not initialized")
            return@withContext "Error: El modelo no está inicializado"
        }

        try {
            Log.d(TAG, "Generating response for prompt: ${prompt.take(50)}...")

            // Formatear prompt para Gemma 3
            val formattedPrompt = formatPromptForGemma3(prompt)

            // Generar texto usando JNI
            val response = llamaBridge.generateText(formattedPrompt, maxTokens)

            // Limpiar la respuesta
            val cleanedResponse = cleanResponse(response, prompt)

            Log.d(TAG, "Response generated successfully")
            return@withContext cleanedResponse

        } catch (e: Exception) {
            Log.e(TAG, "Exception during generation", e)
            return@withContext "Error: ${e.message}"
        }
    }

    /**
     * Formatea el prompt para Gemma 3
     * Gemma 3 usa un formato específico de chat con tokens especiales
     */
    private fun formatPromptForGemma3(userMessage: String): String {
        return buildString {
            append("<start_of_turn>user\n")
            append(userMessage.trim())
            append("<end_of_turn>\n")
            append("<start_of_turn>model\n")
        }
    }

    /**
     * Limpia la respuesta generada
     * - Remueve el prompt original si está incluido
     * - Remueve tokens de fin de turno
     * - Trim de espacios
     */
    private fun cleanResponse(response: String, originalPrompt: String): String {
        var cleaned = response

        // Si la respuesta incluye el prompt, removerlo
        if (cleaned.startsWith(originalPrompt)) {
            cleaned = cleaned.substring(originalPrompt.length)
        }

        // Remover tokens especiales de Gemma 3
        cleaned = cleaned
            .replace("<start_of_turn>user", "")
            .replace("<start_of_turn>model", "")
            .replace("<end_of_turn>", "")
            .trim()

        // Si está vacío después de limpiar, indicar que no hay respuesta
        if (cleaned.isEmpty()) {
            return "El modelo no generó una respuesta."
        }

        return cleaned
    }

    /**
     * Check if engine is initialized
     */
    fun isInitialized(): Boolean = _isInitialized.get()

    /**
     * Release model resources
     * Should be called when engine is no longer needed
     */
    fun release() {
        try {
            if (_isInitialized.compareAndSet(true, false)) {
                llamaBridge.releaseModel()
                Log.i(TAG, "Model released successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception releasing model", e)
        }
    }
}