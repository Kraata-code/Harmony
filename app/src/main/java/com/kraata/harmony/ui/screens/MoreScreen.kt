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

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.harmony.music.identifier.MusicIdentificationResult
import com.harmony.music.identifier.MusicIdentifierService
import com.harmony.music.identifier.NativeNowPlayingMatch
import com.harmony.music.identifier.NativeNowPlayingMatcher
import com.kraata.harmony.BuildConfig
import com.kraata.harmony.LocalPlayerAwareWindowInsets
import com.kraata.harmony.LocalPlayerConnection
import com.kraata.harmony.R
import com.kraata.harmony.service.AcoustIdClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicRecognitionScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current
    val currentSong = playerConnection?.currentSong?.collectAsState(initial = null)?.value
    val currentLocalFile = currentSong?.song?.let { song ->
        if (song.isLocal) song.localPath?.let(::File) else null
    }
    var isProcessing by remember { mutableStateOf(false) }
    var isLookingUp by remember { mutableStateOf(false) }
    var match by remember { mutableStateOf<NativeNowPlayingMatch?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var recognitionJob by remember { mutableStateOf<Job?>(null) }

    val startRecognition: (File?) -> Unit = { file ->
        recognitionJob?.cancel()
        match = null
        errorMessage = null
        isProcessing = true
        recognitionJob = scope.launch {
            try {
                val service = MusicIdentifierService()
                val identification = if (file == null) {
                    service.identifyFromMicrophone(context)
                } else {
                    service.identifyFromFile(file)
                }
                when (identification) {
                    is MusicIdentificationResult.Success -> {
                        isLookingUp = true
                        match = if (BuildConfig.DEBUG) {
                            withContext(Dispatchers.Default) {
                                NativeNowPlayingMatcher.recognize(
                                    context,
                                    identification.sample,
                                )
                            }
                        } else {
                            AcoustIdClient().lookup(
                                fingerprint = identification.fingerprint.encoded,
                                durationMs = identification.fingerprint.durationMs,
                            )?.let { NativeNowPlayingMatch(it.title, it.artist, "", emptyList()) }
                        }
                        if (match == null) {
                            errorMessage = resources.getString(R.string.music_recognition_no_match)
                        }
                    }

                    is MusicIdentificationResult.PermissionMissing -> {
                        errorMessage = resources.getString(R.string.music_recognition_permission_required)
                    }

                    is MusicIdentificationResult.ProcessingError -> {
                        errorMessage = identification.message
                    }

                    is MusicIdentificationResult.UnsupportedFormat -> {
                        errorMessage = identification.reason
                    }

                    MusicIdentificationResult.NoAudio -> {
                        errorMessage = resources.getString(R.string.music_recognition_no_audio)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                errorMessage = exception.message ?: resources.getString(R.string.music_recognition_error)
            } finally {
                isProcessing = false
                isLookingUp = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startRecognition(null)
        } else {
            errorMessage = resources.getString(R.string.music_recognition_permission_required)
        }
    }

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
            if (isProcessing) {
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
                onClick = {
                    if (isProcessing) {
                        recognitionJob?.cancel()
                        isProcessing = false
                        isLookingUp = false
                    } else if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        startRecognition(null)
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                shape = CircleShape,
                containerColor = if (isProcessing) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
                contentColor = if (isProcessing) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                },
            ) {
                Icon(
                    imageVector = Icons.Rounded.Mic,
                    contentDescription = stringResource(
                        if (isProcessing) {
                            R.string.music_recognition_stop
                        } else {
                            R.string.music_recognition_start
                        },
                    ),
                )
            }
        }

        if (BuildConfig.DEBUG && currentLocalFile != null) {
            TextButton(
                onClick = { startRecognition(currentLocalFile) },
                enabled = !isProcessing,
            ) {
                Text(stringResource(R.string.music_recognition_test_file))
            }
        }

        Text(
            text = stringResource(
                if (isLookingUp) {
                    R.string.music_recognition_recognizing
                } else if (isProcessing) {
                    R.string.music_recognition_listening
                } else if (errorMessage != null) {
                    R.string.music_recognition_error
                } else {
                    R.string.music_recognition_placeholder_description
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        match?.let { identified ->
            Text(
                text = stringResource(
                    R.string.music_recognition_result,
                    identified.title,
                    identified.artist,
                ),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
