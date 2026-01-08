package com.dd3boh.outertune.viewmodels

import android.util.Log
import com.dd3boh.outertune.viewmodels.LlamaEngine.Companion.TAG
import com.dd3boh.outertune.viewmodels.LlamaEngine.ToolCall

class AIToolsViewModel {
     fun isToolCall(response: String): Boolean {
        val cleaned = response
            .replace("<|im_start|>", "")
            .replace("<|im_end|>", "")
            .replace("assistant", "")
            .trim()
        return cleaned.contains("\"tool\"") && cleaned.contains("\"arguments\"")
    }

     fun parseToolCall(response: String): ToolCall? {
        return try {
            val cleaned = response
                .replace("<|im_start|>", "")
                .replace("<|im_end|>", "")
                .replace("assistant", "")
                .trim()

            val json = org.json.JSONObject(cleaned)
            val toolName = json.getString("tool")
            val argsObject = json.getJSONObject("arguments")

            val args = mutableMapOf<String, String>()
            argsObject.keys().forEach { key ->
                args[key] = argsObject.getString(key)
            }

            ToolCall(toolName, args)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse tool call", e)
            null
        }
    }

     fun getDateInfo(): String {
        val today = java.time.LocalDate.now()
        return "Internet OK – fecha: $today"
    }
    fun getInternetInfo(query: String): String {
        val today = java.time.LocalDate.now()
        return "Internet OK – fecha: $today"
    }
}