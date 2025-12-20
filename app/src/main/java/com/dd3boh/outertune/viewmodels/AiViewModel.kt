package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.models.ChatMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AiViewModel @Inject constructor() : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        viewModelScope.launch {
            val userMessage = ChatMessage(
                text = text,
                isFromMe = true
            )
            _messages.value = _messages.value + userMessage

            _isLoading.value = true
            delay(1000)

            val botResponse = ChatMessage(
                text = generateBotResponse(text),
                isFromMe = false
            )
            _messages.value = _messages.value + botResponse
            _isLoading.value = false
        }
    }

    fun clearMessages() {
        _messages.value = emptyList()
    }

    private fun generateBotResponse(userMessage: String): String {
        return when {
            userMessage.contains("hola", ignoreCase = true) ->
                "¡Hola! ¿En qué puedo ayudarte hoy?"

            userMessage.contains("ayuda", ignoreCase = true) ->
                "Estoy aquí para ayudarte. ¿Qué necesitas?"

            userMessage.contains("adiós", ignoreCase = true) ->
                "¡Hasta luego! Que tengas un excelente día."

            else ->
                "Entiendo. ¿Hay algo más en lo que pueda ayudarte?"
        }
    }
}