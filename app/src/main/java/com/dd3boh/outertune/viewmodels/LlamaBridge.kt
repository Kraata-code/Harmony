package com.dd3boh.outertune.viewmodels

/**
 * Bridge between Kotlin and native llama.cpp library
 * Handles JNI calls for LLM operations
 */
class LlamaBridge {

    /**
     * Initialize the LLM model from file
     * @param path Absolute path to the GGUF model file
     * @return true if initialization successful, false otherwise
     */
    external fun initModel(path: String): Boolean

    /**
     * Generate text based on a prompt
     * @param prompt The input text prompt
     * @param maxTokens Maximum number of tokens to generate (default: 128)
     * @return Generated text response
     */
    external fun generateText(prompt: String, maxTokens: Int = 128): String

    /**
     * Release model resources
     * Should be called when the model is no longer needed
     */
    external fun releaseModel()

    companion object {
        init {
            System.loadLibrary("llama-jni-lib")
        }
    }
}