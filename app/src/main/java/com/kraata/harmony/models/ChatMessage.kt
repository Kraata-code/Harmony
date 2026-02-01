package com.kraata.harmony.models

data class ChatMessage(
    val text: String,
    val isFromMe: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val id: String = "${timestamp}_${if (isFromMe) "user" else "ai"}"
)