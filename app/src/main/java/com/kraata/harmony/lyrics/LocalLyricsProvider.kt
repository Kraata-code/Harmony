/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 * Copyright (C) 2026 Harmony Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.kraata.harmony.lyrics

import android.content.Context
import org.akanework.gramophone.logic.utils.LrcUtils
import org.akanework.gramophone.logic.utils.LrcUtils.loadAndParseLyricsFile
import org.akanework.gramophone.logic.utils.SemanticLyrics
import java.io.File


object LocalLyricsProvider : LyricsProvider {
    override val name = "Local LRC"
    override fun isEnabled(context: Context) = true

    /**
     * This function is "hot-wired" to adapted to the
     * interface design. As a result, title is actually the file path.
     * The lrc file is assumed to be in the same directory as the song.
     * All the other fields serve no purpose.
     *
     * @param title file path of the song, NOT the song title
     */
    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
    ): Result<String> {
        throw NotImplementedError()
    }

    fun getLyricsNew(
        path: String,
        parserOptions: LrcUtils.LrcParserOptions
    ): SemanticLyrics? {
        // TODO: audiomimetype
        return loadAndParseLyricsFile(File(path), null, parserOptions)
    }

}
