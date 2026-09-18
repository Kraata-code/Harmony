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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.harmony.music.identifier.MusicIdentificationResult
import com.harmony.music.identifier.MusicIdentifierService
import com.harmony.music.identifier.NativeNowPlayingMatch
import com.harmony.music.identifier.NativeNowPlayingMatcher
import com.kraata.harmony.BuildConfig
import com.kraata.harmony.LocalPlayerAwareWindowInsets
import com.kraata.harmony.LocalPlayerConnection
import com.kraata.harmony.R
import com.kraata.harmony.constants.NowPlayingShardGroupKey
import com.kraata.harmony.data.NowPlayingAssetsDownloader
import com.kraata.harmony.models.toMediaMetadata
import com.kraata.harmony.playback.queues.ListQueue
import com.kraata.harmony.utils.getThumbnailModel
import com.kraata.harmony.utils.rememberPreference
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
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
    var matchedSong by remember { mutableStateOf<SongItem?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var recognitionJob by remember { mutableStateOf<Job?>(null) }
    val (storedShardGroup) = rememberPreference(
        NowPlayingShardGroupKey,
        NowPlayingAssetsDownloader.defaultShardGroup(),
    )
    val shardGroup = storedShardGroup.takeIf {
        it == NativeNowPlayingMatcher.MX_SHARD_GROUP || it == NativeNowPlayingMatcher.US_XA_SHARD_GROUP
    } ?: NowPlayingAssetsDownloader.defaultShardGroup()

    val startRecognition: (File?) -> Unit = { file ->
        recognitionJob?.cancel()
        match = null
        matchedSong = null
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
                        val nativeMatch = if (NativeNowPlayingMatcher.isReady(context, shardGroup)) {
                            try {
                                withContext(Dispatchers.Default) {
                                    NativeNowPlayingMatcher.recognize(
                                        context,
                                        identification.sample,
                                        shardGroup,
                                    )
                                }
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (_: Exception) {
                                null
                            }
                        } else null
                        val identifiedMatch = nativeMatch
                        match = identifiedMatch
                        if (identifiedMatch == null) {
                            errorMessage = resources.getString(R.string.music_recognition_no_match)
                        } else {
                            matchedSong = try {
                                YouTube.search(
                                    "${identifiedMatch.artist} ${identifiedMatch.title}",
                                    YouTube.SearchFilter.FILTER_SONG,
                                ).getOrNull()?.items.orEmpty()
                                    .filterIsInstance<SongItem>()
                                    .firstOrNull()
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (_: Exception) {
                                null
                            }
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

    val toggleRecognition = {
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
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
    ) {
        matchedSong?.let { song ->
            AsyncImage(
                model = getThumbnailModel(song.thumbnail, 544, 544),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(100.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
            )
        }

        AnimatedContent(
            targetState = match,
            transitionSpec = {
                fadeIn(tween(350)).togetherWith(fadeOut(tween(200)))
            },
            label = "recognitionContent",
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current),
        ) { identified ->
            if (identified == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
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
                            onClick = toggleRecognition,
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
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .padding(bottom = 56.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(260.dp)
                                .clip(MaterialTheme.shapes.large)
                                .clickable(enabled = matchedSong != null) {
                                    matchedSong?.let { song ->
                                        playerConnection?.playQueue(
                                            ListQueue(
                                                title = song.title,
                                                items = listOf(song.toMediaMetadata()),
                                            ),
                                        )
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (matchedSong != null) {
                                AsyncImage(
                                    model = getThumbnailModel(matchedSong?.thumbnail, 1080, 1080),
                                    contentDescription = identified.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.MusicNote,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(72.dp),
                                )
                            }
                        }
                        Text(
                            text = matchedSong?.title ?: identified.title,
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 20.dp),
                        )
                        Text(
                            text = matchedSong?.artists?.joinToString { it.name } ?: identified.artist,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        if (isLookingUp) {
                            Text(
                                text = stringResource(R.string.music_recognition_recognizing),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 16.dp),
                            )
                        }
                    }

                    SmallFloatingActionButton(
                        onClick = toggleRecognition,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp),
                        shape = CircleShape,
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
            }
        }
    }
}
