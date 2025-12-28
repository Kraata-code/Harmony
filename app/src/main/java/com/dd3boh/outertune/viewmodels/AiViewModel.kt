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

    private companion object {
        const val TAG = "AiViewModel"
        // Límite de tokens optimizado para respuestas rápidas
        const val MAX_TOKENS_PER_RESPONSE = 80
    }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var llamaEngine: LlamaEngine? = null

    /**
     * Initialize the LLM engine
     * Must be called before sending messages
     */
    fun initEngine(context: MainActivity) {
        if (_isInitialized.value || llamaEngine != null) {
            Log.i(TAG, "Engine already initialized or initializing")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "Starting engine initialization...")

                llamaEngine = LlamaEngine(context)
                val success = llamaEngine!!.init()

                withContext(Dispatchers.Main) {
                    _isInitialized.value = success

                    if (success) {
                        Log.i(TAG, "LLM Engine initialized successfully")
                        _errorMessage.value = null

                        _messages.value = listOf(
                            ChatMessage(
                                text = "¡Hola! Soy tu asistente. ¿En qué puedo ayudarte?",
                                isFromMe = false
                            )
                        )
                    } else {
                        Log.e(TAG, "Failed to initialize LLM Engine")
                        _errorMessage.value = "No se pudo inicializar el modelo de IA"

                        _messages.value = listOf(
                            ChatMessage(
                                text = "Error: No se pudo inicializar el modelo. Verifica que el archivo esté en la carpeta correcta.",
                                isFromMe = false
                            )
                        )

                        llamaEngine = null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception initializing engine", e)

                withContext(Dispatchers.Main) {
                    _isInitialized.value = false
                    _errorMessage.value = e.message ?: "Error desconocido"

                    _messages.value = listOf(
                        ChatMessage(
                            text = "Error durante la inicialización: ${e.message}",
                            isFromMe = false
                        )
                    )

                    llamaEngine = null
                }
            }
        }
    }

    /**
     * Send a message and get AI response
     */
    fun sendMessage(text: String) {
        if (text.isBlank()) {
            Log.w(TAG, "Attempted to send blank message")
            return
        }

        if (!_isInitialized.value) {
            Log.w(TAG, "Engine not initialized")

            val errorMsg = ChatMessage(
                text = "Por favor espera a que el modelo se inicialice antes de enviar mensajes.",
                isFromMe = false
            )
            _messages.value = _messages.value + errorMsg
            return
        }

        if (llamaEngine == null) {
            Log.e(TAG, "Engine instance is null despite initialized flag")

            val errorMsg = ChatMessage(
                text = "Error interno: El motor no está disponible. Intenta reiniciar la aplicación.",
                isFromMe = false
            )
            _messages.value = _messages.value + errorMsg
            return
        }

        viewModelScope.launch {
            try {
                // Agregar mensaje del usuario
                val userMessage = ChatMessage(
                    text = text,
                    isFromMe = true
                )
                _messages.value = _messages.value + userMessage

                _isLoading.value = true
                _errorMessage.value = null

                Log.d(TAG, "Generating response for: ${text.take(50)}...")

                // Generar respuesta con límite optimizado
                val response = withContext(Dispatchers.IO) {
                    try {
                        val engine = llamaEngine
                        if (engine == null || !engine.isInitialized()) {
                            "Error: El motor no está disponible"
                        } else {
                            // Usar límite de tokens optimizado
                            engine.generateResponse(text, maxTokens = MAX_TOKENS_PER_RESPONSE)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in generation coroutine", e)
                        "Error: ${e.message}"
                    }
                }

                // Agregar respuesta del bot
                val botResponse = ChatMessage(
                    text = response,
                    isFromMe = false
                )
                _messages.value = _messages.value + botResponse

                Log.d(TAG, "Response added to messages")

            } catch (e: Exception) {
                Log.e(TAG, "Error generating response", e)

                val errorMessage = ChatMessage(
                    text = "Error generando respuesta: ${e.message}",
                    isFromMe = false
                )
                _messages.value = _messages.value + errorMessage

                _errorMessage.value = e.message

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
        _errorMessage.value = null

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
     * Retry initialization if failed
     */
    fun retryInitialization(context: MainActivity) {
        Log.i(TAG, "Retrying initialization...")

        llamaEngine?.release()
        llamaEngine = null
        _isInitialized.value = false
        _errorMessage.value = null
        _messages.value = emptyList()

        initEngine(context)
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * Clean up resources when ViewModel is cleared
     */
    override fun onCleared() {
        super.onCleared()

        Log.i(TAG, "ViewModel clearing, releasing resources...")

        try {
            llamaEngine?.release()
            llamaEngine = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing engine in onCleared", e)
        }

        Log.i(TAG, "ViewModel cleared, resources released")
    }
}