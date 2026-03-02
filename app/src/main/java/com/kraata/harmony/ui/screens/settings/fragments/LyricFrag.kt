/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 * Copyright (C) 2026 Harmony Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.kraata.harmony.ui.screens.settings.fragments

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.TextRotationAngledown
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kraata.harmony.R
import com.kraata.harmony.constants.EnableKugouKey
import com.kraata.harmony.constants.EnableLrcLibKey
import com.kraata.harmony.constants.LyricClickable
import com.kraata.harmony.constants.LyricFontSizeKey
import com.kraata.harmony.constants.LyricKaraokeEnable
import com.kraata.harmony.constants.LyricSourcePrefKey
import com.kraata.harmony.constants.LyricTrimKey
import com.kraata.harmony.constants.LyricUpdateSpeed
import com.kraata.harmony.constants.LyricsPosition
import com.kraata.harmony.constants.LyricsTextPositionKey
import com.kraata.harmony.constants.MultilineLrcKey
import com.kraata.harmony.constants.Speed
import com.kraata.harmony.ui.component.EnumListPreference
import com.kraata.harmony.ui.component.ListPreference
import com.kraata.harmony.ui.component.PreferenceEntry
import com.kraata.harmony.ui.component.SwitchPreference
import com.kraata.harmony.ui.dialog.CounterDialog
import com.kraata.harmony.utils.rememberEnumPreference
import com.kraata.harmony.utils.rememberPreference

@Composable
fun ColumnScope.LyricFormatFrag() {
    val (lyricsPosition, onLyricsPositionChange) = rememberEnumPreference(
        LyricsTextPositionKey,
        defaultValue = LyricsPosition.CENTER
    )
    val (lyricFontSize, onLyricFontSizeChange) = rememberPreference(LyricFontSizeKey, defaultValue = 20)

    var showFontSizeDialog by remember {
        mutableStateOf(false)
    }

    EnumListPreference(
        title = { Text(stringResource(R.string.lyrics_text_position)) },
        icon = { Icon(Icons.Rounded.Lyrics, null) },
        selectedValue = lyricsPosition,
        onValueSelected = onLyricsPositionChange,
        valueText = {
            when (it) {
                LyricsPosition.LEFT -> stringResource(R.string.left)
                LyricsPosition.CENTER -> stringResource(R.string.center)
                LyricsPosition.RIGHT -> stringResource(R.string.right)
            }
        }
    )
    PreferenceEntry(
        title = { Text(stringResource(R.string.lyrics_font_Size)) },
        description = "$lyricFontSize sp",
        icon = { Icon(Icons.Rounded.TextFields, null) },
        onClick = { showFontSizeDialog = true }
    )


    /**
     * ---------------------------
     * Dialogs
     * ---------------------------
     */


    if (showFontSizeDialog) {
        CounterDialog(
            title = stringResource(R.string.lyrics_font_Size),
            initialValue = lyricFontSize,
            upperBound = 32,
            lowerBound = 8,
            unitDisplay = " pt",
            onDismiss = { showFontSizeDialog = false },
            onConfirm = {
                onLyricFontSizeChange(it)
                showFontSizeDialog = false
            },
            onReset = { onLyricFontSizeChange(20) },
            onCancel = { showFontSizeDialog = false }
        )
    }
}


@Composable
fun ColumnScope.LyricParserFrag() {
    val (multilineLrc, onMultilineLrcChange) = rememberPreference(MultilineLrcKey, defaultValue = true)
    val (lyricTrim, onLyricTrimChange) = rememberPreference(LyricTrimKey, defaultValue = false)

    // multiline lyrics
    SwitchPreference(
        title = { Text(stringResource(R.string.lyrics_multiline_title)) },
        description = stringResource(R.string.lyrics_multiline_description),
        icon = { Icon(Icons.AutoMirrored.Rounded.Sort, null) },
        checked = multilineLrc,
        onCheckedChange = onMultilineLrcChange
    )

    // trim (remove spaces around) lyrics
    SwitchPreference(
        title = { Text(stringResource(R.string.lyrics_trim_title)) },
        icon = { Icon(Icons.Rounded.ContentCut, null) },
        checked = lyricTrim,
        onCheckedChange = onLyricTrimChange
    )
}

@Composable
fun ColumnScope.LyricSourceFrag() {
    val (enableKugou, onEnableKugouChange) = rememberPreference(key = EnableKugouKey, defaultValue = true)
    val (enableLrcLib, onEnableLrcLibChange) = rememberPreference(key = EnableLrcLibKey, defaultValue = true)
    val (preferLocalLyric, onPreferLocalLyric) = rememberPreference(LyricSourcePrefKey, defaultValue = true)

    SwitchPreference(
        title = { Text(stringResource(R.string.enable_lrclib)) },
        icon = { Icon(Icons.Rounded.Lyrics, null) },
        checked = enableLrcLib,
        onCheckedChange = onEnableLrcLibChange
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.enable_kugou)) },
        icon = { Icon(Icons.Rounded.Lyrics, null) },
        checked = enableKugou,
        onCheckedChange = onEnableKugouChange
    )
    // prioritize local lyric files over all cloud providers
    SwitchPreference(
        title = { Text(stringResource(R.string.lyrics_prefer_local)) },
        description = stringResource(R.string.lyrics_prefer_local_description),
        icon = { Icon(Icons.Rounded.ContentCut, null) },
        checked = preferLocalLyric,
        onCheckedChange = onPreferLocalLyric
    )
}

@Composable
fun ColumnScope.LyricAdvancedFrag() {
    val (lyricUpdateSpeed, onLyricsUpdateSpeedChange) = rememberEnumPreference(LyricUpdateSpeed, Speed.MEDIUM)
    val (lyricsFancy, onLyricsFancyChange) = rememberPreference(LyricKaraokeEnable, false)
    val (syncedLyricsClickable, onSyncedLyricsClickable) = rememberPreference(LyricClickable, defaultValue = true)

    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        // clickable lyrics
        SwitchPreference(
            title = { Text(stringResource(R.string.lyrics_synced_clickable)) },
            icon = { Icon(Icons.Rounded.TouchApp, null) },
            checked = syncedLyricsClickable,
            onCheckedChange = onSyncedLyricsClickable
        )
    }
    Spacer(modifier = Modifier.height(16.dp))

    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        SwitchPreference(
            title = { Text(stringResource(R.string.lyrics_karaoke_title)) },
            description = stringResource(R.string.lyrics_karaoke_description),
            icon = { Icon(Icons.Rounded.TextRotationAngledown, null) },
            checked = lyricsFancy,
            onCheckedChange = onLyricsFancyChange
        )

        ListPreference(
            title = { Text(stringResource(R.string.lyrics_karaoke_hz_title)) },
            icon = { Icon(Icons.Rounded.Speed, null) },
            selectedValue = lyricUpdateSpeed,
            onValueSelected = onLyricsUpdateSpeedChange,
            values = Speed.entries,
            valueText = {
                when (it) {
                    Speed.SLOW -> stringResource(R.string.speed_slow)
                    Speed.MEDIUM -> stringResource(R.string.speed_medium)
                    Speed.FAST -> stringResource(R.string.speed_fast)
                }
            },
            isEnabled = lyricsFancy
        )
    }
}
