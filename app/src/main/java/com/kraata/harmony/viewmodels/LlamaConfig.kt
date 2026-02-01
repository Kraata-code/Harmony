package com.kraata.harmony.viewmodels

/**
 * Configuración de generación para llama.cpp
 * Ajusta estos valores para mejorar calidad y velocidad
 */
data class GenerationConfig(
    // Número máximo de tokens a generar
    val maxTokens: Int = 100,

    // Temperatura: mayor = más creativo, menor = más predecible
    // Rango: 0.1 - 2.0
    val temperature: Float = 0.7f,

    // Top-K: considera solo los K tokens más probables
    // Rango: 1 - 100
    val topK: Int = 40,

    // Top-P: considera tokens cuya probabilidad acumulada es P
    // Rango: 0.0 - 1.0
    val topP: Float = 0.9f,

    // Penalización por repetición
    // Mayor = menos repetición
    val repeatPenalty: Float = 1.1f,

    // Tokens de parada (stop sequences)
    val stopSequences: List<String> = listOf(
        "<end_of_turn>",
        "<start_of_turn>",
        "\nuser",
        "\nmodel"
    )
) {
    companion object {
        /**
         * Configuración para respuestas cortas y rápidas
         */
        fun quickResponse() = GenerationConfig(
            maxTokens = 50,
            temperature = 0.6f,
            topK = 30,
            topP = 0.85f
        )

        /**
         * Configuración balanceada (por defecto)
         */
        fun balanced() = GenerationConfig(
            maxTokens = 100,
            temperature = 0.7f,
            topK = 40,
            topP = 0.9f
        )

        /**
         * Configuración para respuestas más largas y creativas
         */
        fun creative() = GenerationConfig(
            maxTokens = 200,
            temperature = 0.9f,
            topK = 50,
            topP = 0.95f
        )

        /**
         * Configuración para respuestas muy precisas
         */
        fun precise() = GenerationConfig(
            maxTokens = 80,
            temperature = 0.3f,
            topK = 20,
            topP = 0.8f
        )
    }
}

/**
 * Historial de conversación con límite
 */
class ConversationHistory(
    private val maxHistory: Int = 5
) {
    private val history = mutableListOf<Pair<String, String>>()

    /**
     * Agrega un intercambio (usuario + asistente) al historial
     */
    fun add(userMessage: String, assistantResponse: String) {
        history.add(userMessage to assistantResponse)

        // Mantener solo los últimos N intercambios
        if (history.size > maxHistory) {
            history.removeAt(0)
        }
    }

    /**
     * Construye un prompt con contexto del historial
     */
    fun buildPromptWithContext(currentMessage: String): String {
        return buildString {
            append("<bos>")

            // Agregar historial previo
            for ((user, assistant) in history) {
                append("<start_of_turn>user\n")
                append(user)
                append("<end_of_turn>\n")
                append("<start_of_turn>model\n")
                append(assistant)
                append("<end_of_turn>\n")
            }

            // Agregar mensaje actual
            append("<start_of_turn>user\n")
            append(currentMessage)
            append("<end_of_turn>\n")
            append("<start_of_turn>model\n")
        }
    }

    /**z
     * Limpia el historial
     */
    fun clear() {
        history.clear()
    }

    /**
     * Obtiene el tamaño del historial
     */
    fun size(): Int = history.size
}