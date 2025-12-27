package com.dd3boh.outertune.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.models.ChatMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class AiViewModel @Inject constructor() : ViewModel() {

    private val TAG = "AiViewModel"

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private var llamaEngine: LlamaEngine? = null

    /**
     * Initialize the LLM engine
     * Must be called before sending messages
     */
    fun initEngine(context: MainActivity) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (llamaEngine == null) {
                    llamaEngine = LlamaEngine(context)
                    val success = llamaEngine!!.init()

                    withContext(Dispatchers.Main) {
                        _isInitialized.value = success
                        if (success) {
                            Log.i(TAG, "LLM Engine initialized successfully")
                            // Add welcome message
                            _messages.value = listOf(
                                ChatMessage(
                                    text = "¡Hola! Soy tu asistente de IA. ¿En qué puedo ayudarte hoy?",
                                    isFromMe = false
                                )
                            )
                        } else {
                            Log.e(TAG, "Failed to initialize LLM Engine")
                            _messages.value = listOf(
                                ChatMessage(
                                    text = "Error: No se pudo inicializar el modelo de IA",
                                    isFromMe = false
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception initializing engine", e)
                withContext(Dispatchers.Main) {
                    _isInitialized.value = false
                    _messages.value = listOf(
                        ChatMessage(
                            text = "Error: ${e.message}",
                            isFromMe = false
                        )
                    )
                }
            }
        }
    }

    /**
     * Send a message and get AI response
     */
    fun sendMessage(text: String) {
        if (text.isBlank()) return
        if (!_isInitialized.value) {
            Log.w(TAG, "Engine not initialized")
            return
        }

        viewModelScope.launch {
            try {
                // Add user message
                val userMessage = ChatMessage(
                    text = text,
                    isFromMe = true
                )
                _messages.value = _messages.value + userMessage

                _isLoading.value = true

                // Generate response in background
                val response = withContext(Dispatchers.IO) {
                    llamaEngine?.generateResponse(text) ?: "Error: Engine not available"
                }

                // Add bot response
                val botResponse = ChatMessage(
                    text = response,
                    isFromMe = false
                )
                _messages.value = _messages.value + botResponse

            } catch (e: Exception) {
                Log.e(TAG, "Error generating response", e)
                val errorMessage = ChatMessage(
                    text = "Error: ${e.message}",
                    isFromMe = false
                )
                _messages.value = _messages.value + errorMessage
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Clear all messages
     */
    fun clearMessages() {
        _messages.value = emptyList()
        // Add welcome message back
        if (_isInitialized.value) {
            _messages.value = listOf(
                ChatMessage(
                    text = "Conversación reiniciada. ¿En qué puedo ayudarte?",
                    isFromMe = false
                )
            )
        }
    }

    /**
     * Clean up resources when ViewModel is cleared
     */
    override fun onCleared() {
        super.onCleared()
        llamaEngine?.release()
        llamaEngine = null
        Log.i(TAG, "ViewModel cleared, resources released")
    }
}