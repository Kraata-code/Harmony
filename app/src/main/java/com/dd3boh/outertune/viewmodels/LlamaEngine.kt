package com.dd3boh.outertune.viewmodels

import android.util.Log
import com.dd3boh.outertune.MainActivity

/**
 * Engine for managing LLM operations
 * Handles model initialization and text generation
 */
class LlamaEngine(private val context: MainActivity) {

    private val TAG = "LlamaEngine"
    private val bridge = LlamaBridge()
    private var initialized = false

    /**
     * Initialize the LLM model
     * @return true if successful, false otherwise
     */
    fun init(): Boolean {
        if (initialized) {
            Log.d(TAG, "Engine already initialized")
            return true
        }

        try {
            // Prepare model file
            val path = ModelManager.prepareModel(context)
            Log.i(TAG, "Model path: $path")

            // Initialize model via JNI
            initialized = bridge.initModel(path)

            if (initialized) {
                Log.i(TAG, "Model initialized successfully")
            } else {
                Log.e(TAG, "Failed to initialize model")
            }

            return initialized

        } catch (e: Exception) {
            Log.e(TAG, "Exception during initialization", e)
            return false
        }
    }

    /**
     * Generate a response for the given prompt
     * @param prompt User input text
     * @param maxTokens Maximum tokens to generate (default: 128)
     * @return Generated response text
     */
    fun generateResponse(prompt: String, maxTokens: Int = 128): String {
        if (!initialized) {
            Log.w(TAG, "Attempted to generate without initialization")
            return "Error: Model not initialized"
        }

        return try {
            Log.d(TAG, "Generating response for: $prompt")

            // Format prompt for better results
            val formattedPrompt = formatPrompt(prompt)

            // Call native generation method
            val response = bridge.generateText(formattedPrompt, maxTokens)

            Log.d(TAG, "Generated response: ${response.take(50)}...")
            response.trim()

        } catch (e: Exception) {
            Log.e(TAG, "Exception during generation", e)
            "Error: ${e.message}"
        }
    }

    /**
     * Format the prompt for better model performance
     */
    private fun formatPrompt(userInput: String): String {
        // Simple chat template for Gemma
        return """<start_of_turn>user
$userInput<end_of_turn>
<start_of_turn>model
"""
    }

    /**
     * Release model resources
     */
    fun release() {
        if (initialized) {
            try {
                bridge.releaseModel()
                initialized = false
                Log.i(TAG, "Model released successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing model", e)
            }
        }
    }
}