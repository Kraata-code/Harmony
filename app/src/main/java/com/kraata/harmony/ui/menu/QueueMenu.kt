/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 * Copyright (C) 2026 Harmony Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.kraata.harmony.ui.menu

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.kraata.harmony.LocalPlayerConnection
import com.kraata.harmony.R
import com.kraata.harmony.models.MultiQueueObject
import com.kraata.harmony.ui.component.items.QueueListItem
import com.kraata.harmony.ui.dialog.AddToPlaylistDialog
import com.kraata.harmony.ui.dialog.AddToQueueDialog
import com.kraata.harmony.ui.dialog.EditQueueDialog

@Composable
fun QueueMenu(
    navController: NavController,
    mq: MultiQueueObject?,
    onDismiss: () -> Unit,
) {
    val playerConnection = LocalPlayerConnection.current ?: return

    if (mq == null) {
        onDismiss()
        return
    }
    val songs = mq.getCurrentQueueShuffled()

    var showChoosePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var showChooseQueueDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var showEditDialog by rememberSaveable {
        mutableStateOf(false)
    }

    // queue item
    QueueListItem(queue = mq)

    HorizontalDivider()

    // menu options
    GridMenu(
        contentPadding = PaddingValues(
            start = 8.dp,
            top = 8.dp,
            end = 8.dp,
            bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
        )
    ) {
        GridMenuItem(
            icon = Icons.AutoMirrored.Rounded.QueueMusic,
            title = R.string.add_to_queue
        ) {
            showChooseQueueDialog = true
        }
        GridMenuItem(
            icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
            title = R.string.add_to_playlist
        ) {
            showChoosePlaylistDialog = true
        }
        GridMenuItem(
            icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
            title = R.string.edit
        ) {
            showEditDialog = true
        }
    }

    /**
     * ---------------------------
     * Dialogs
     * ---------------------------
     */
    if (showChoosePlaylistDialog) {
        AddToPlaylistDialog(
            navController = navController,
            songIds = songs.map { it.id },
            onDismiss = {
                showChoosePlaylistDialog = false
            }
        )
    }

    if (showChooseQueueDialog) {
        AddToQueueDialog(
            onAdd = { queueName ->
                val q = playerConnection.service.queueBoard.addQueue(
                    queueName,
                    songs,
                    forceInsert = true,
                    delta = false
                )
                q?.let {
                    playerConnection.service.queueBoard.setCurrQueue(it)
                }
            },
            onDismiss = {
                showChooseQueueDialog = false
                onDismiss() // here we dismiss since we switch to the queue anyways
            }
        )
    }

    if (showEditDialog) {
        EditQueueDialog(
            queue = mq,
            onDismiss = {
                showEditDialog = false
                onDismiss()
            }
        )
    }
}
