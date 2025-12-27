package com.dd3boh.outertune.viewmodels

import android.content.Context
import java.io.File

object ModelManager {

    fun prepareModel(context: Context): String {
        val outFile = File(context.filesDir, "gemma-3-270m-it-Q8_0.gguf")

        if (!outFile.exists()) {
            context.assets.open("gemma-3-270m-it-Q8_0.gguf").use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        return outFile.absolutePath
    }
}
