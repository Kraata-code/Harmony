package com.dd3boh.outertune.viewmodels

import android.content.Context
import java.io.File

object ModelManager {

    fun prepareModel(context: Context): String {
        val outFile = File(context.filesDir, "Qwen2-500M-Instruct-IQ4_XS.gguf")

        if (!outFile.exists()) {
            context.assets.open("Qwen2-500M-Instruct-IQ4_XS.gguf").use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        return outFile.absolutePath
    }
}
