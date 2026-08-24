/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 * Copyright (C) 2026 Harmony Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.kraata.harmony.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.kraata.harmony.LocalPlayerAwareWindowInsets
import com.kraata.harmony.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicRecognitionScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    var isListening by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.music_recognition_placeholder_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            modifier = Modifier
                .padding(vertical = 24.dp)
                .size(200.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isListening) {
                val infiniteTransition = rememberInfiniteTransition(label = "recognitionWaves")
                val waveProgress by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1800, easing = LinearEasing),
                    ),
                    label = "waveProgress",
                )
                val waveColor = MaterialTheme.colorScheme.primary

                Canvas(Modifier.matchParentSize()) {
                    val buttonRadius = 48.dp.toPx()
                    val maxRadius = size.minDimension / 2f

                    listOf(0f, 1f / 3f, 2f / 3f).forEach { phase ->
                        val progress = (waveProgress + phase) % 1f
                        drawCircle(
                            color = waveColor.copy(
                                alpha = 0.35f * (1f - progress),
                            ),
                            radius = buttonRadius + (maxRadius - buttonRadius) * progress,
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
            }

            LargeFloatingActionButton(
                onClick = { isListening = !isListening },
                shape = CircleShape,
                containerColor = if (isListening) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
                contentColor = if (isListening) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                },
            ) {
                Icon(
                    imageVector = Icons.Rounded.Mic,
                    contentDescription = stringResource(
                        if (isListening) {
                            R.string.music_recognition_stop
                        } else {
                            R.string.music_recognition_start
                        },
                    ),
                )
            }
        }

        Text(
            text = stringResource(
                if (isListening) {
                    R.string.music_recognition_listening
                } else {
                    R.string.music_recognition_placeholder_description
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
