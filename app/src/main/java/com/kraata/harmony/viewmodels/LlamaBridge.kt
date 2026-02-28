package com.kraata.harmony.viewmodels

import android.util.Log

/**
 * Bridge between Kotlin and native llama.cpp library
 * Handles JNI calls for LLM operations
 */
class LlamaBridge {

    /**
     * Initialize the LLM model from file
     * @param path Absolute path to the GGUF model file
     * @param contextLength Requested context length (tokens)
     * @return true if initialization successful, false otherwise
     */
    external fun initModel(path: String, contextLength: Int): Boolean

    /**
     * Generate text based on a prompt
     * @param prompt The input text prompt
     * @param maxTokens Maximum number of tokens to generate (default: 128)
     * @return Generated text response
     */
    external fun generateText(prompt: String, maxTokens: Int = 50): String

    /**
     * Release model resources
     * Should be called when the model is no longer needed
     */
    external fun releaseModel()
    external fun clearConversation()
    external fun getContextInfo(): String

    companion object {
        @Volatile
        private var nativeLibraryLoaded = false

        init {
            nativeLibraryLoaded = try {
                System.loadLibrary("llama-jni-lib")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.e("LlamaBridge", "Failed to load llama-jni-lib", e)
                false
            }
        }

        fun isNativeLibraryLoaded(): Boolean {
            return nativeLibraryLoaded
        }
    }
}
