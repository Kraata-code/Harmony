/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 * Copyright (C) 2026 Harmony Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.kraata.harmony.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.SQLException
import android.media.audiofx.AudioEffect
import android.net.ConnectivityManager
import android.os.Binder
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.EVENT_POSITION_DISCONTINUITY
import androidx.media3.common.Player.EVENT_TIMELINE_CHANGED
import androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
import androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.Player.STATE_IDLE
import androidx.media3.common.Timeline
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioOffloadSupportProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.CommandButton
import androidx.media3.session.CommandButton.ICON_UNDEFINED
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.kraata.harmony.MainActivity
import com.kraata.harmony.R
import com.kraata.harmony.constants.AudioDecoderKey
import com.kraata.harmony.constants.AudioGaplessOffloadKey
import com.kraata.harmony.constants.AudioNormalizationKey
import com.kraata.harmony.constants.AudioOffloadKey
import com.kraata.harmony.constants.AudioQuality
import com.kraata.harmony.constants.AudioQualityKey
import com.kraata.harmony.constants.AutoLoadMoreKey
import com.kraata.harmony.constants.ENABLE_FFMETADATAEX
import com.kraata.harmony.constants.KeepAliveKey
import com.kraata.harmony.constants.MAX_PLAYER_CONSECUTIVE_ERR
import com.kraata.harmony.constants.MaxQueuesKey
import com.kraata.harmony.constants.MediaSessionConstants.CommandToggleLike
import com.kraata.harmony.constants.MediaSessionConstants.CommandToggleRepeatMode
import com.kraata.harmony.constants.MediaSessionConstants.CommandToggleShuffle
import com.kraata.harmony.constants.MediaSessionConstants.CommandToggleStartRadio
import com.kraata.harmony.constants.PauseListenHistoryKey
import com.kraata.harmony.constants.PauseRemoteListenHistoryKey
import com.kraata.harmony.constants.PersistentQueueKey
import com.kraata.harmony.constants.PlayerVolumeKey
import com.kraata.harmony.constants.RepeatModeKey
import com.kraata.harmony.constants.SkipOnErrorKey
import com.kraata.harmony.constants.SkipSilenceKey
import com.kraata.harmony.constants.StopMusicOnTaskClearKey
import com.kraata.harmony.constants.minPlaybackDurKey
import com.kraata.harmony.db.MusicDatabase
import com.kraata.harmony.db.entities.Event
import com.kraata.harmony.db.entities.FormatEntity
import com.kraata.harmony.db.entities.RelatedSongMap
import com.kraata.harmony.di.AppModule.PlayerCache
import com.kraata.harmony.di.DownloadCache
import com.kraata.harmony.extensions.SilentHandler
import com.kraata.harmony.extensions.collect
import com.kraata.harmony.extensions.collectLatest
import com.kraata.harmony.extensions.currentMetadata
import com.kraata.harmony.extensions.findNextMediaItemById
import com.kraata.harmony.extensions.metadata
import com.kraata.harmony.extensions.setOffloadEnabled
import com.kraata.harmony.lyrics.LyricsHelper
import com.kraata.harmony.models.HybridCacheDataSinkFactory
import com.kraata.harmony.models.MediaMetadata
import com.kraata.harmony.models.MultiQueueObject
import com.kraata.harmony.models.toMediaMetadata
import com.kraata.harmony.playback.queues.ListQueue
import com.kraata.harmony.playback.queues.Queue
import com.kraata.harmony.playback.queues.YouTubeQueue
import com.kraata.harmony.utils.CoilBitmapLoader
import com.kraata.harmony.utils.NetworkConnectivityObserver
import com.kraata.harmony.utils.SyncUtils
import com.kraata.harmony.utils.YTPlayerUtils
import com.kraata.harmony.utils.dataStore
import com.kraata.harmony.utils.enumPreference
import com.kraata.harmony.utils.get
import com.kraata.harmony.utils.playerCoroutine
import com.kraata.harmony.utils.reportException
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint
import dagger.hilt.android.AndroidEntryPoint
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.pow

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@AndroidEntryPoint
class MusicService : MediaLibraryService(),
    Player.Listener,
    PlaybackStatsListener.Callback {
    val TAG = MusicService::class.simpleName.toString()

    @Inject
    lateinit var database: MusicDatabase
    private val scope = CoroutineScope(Dispatchers.Main)
    private val offloadScope = CoroutineScope(playerCoroutine)

    // Critical player components
    @Inject
    lateinit var downloadUtil: DownloadUtil

    @Inject
    lateinit var lyricsHelper: LyricsHelper

    @Inject
    lateinit var mediaLibrarySessionCallback: MediaLibrarySessionCallback

    private val binder = MusicBinder()
    private lateinit var connectivityManager: ConnectivityManager

    val qbInit = MutableStateFlow(false)
    var queueBoard = QueueBoard(this, maxQueues = 1)
    var queuePlaylistId: String? = null

    @Inject
    @PlayerCache
    lateinit var playerCache: SimpleCache

    @Inject
    @DownloadCache
    lateinit var downloadCache: SimpleCache

    lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaLibrarySession

    // Player components
    @Inject
    lateinit var syncUtils: SyncUtils

    lateinit var connectivityObserver: NetworkConnectivityObserver
    val waitingForNetworkConnection = MutableStateFlow(false)
    private val isNetworkConnected = MutableStateFlow(true)

    lateinit var sleepTimer: SleepTimer

    // Player vars
    val currentMediaMetadata = MutableStateFlow<MediaMetadata?>(null)

    private val currentSong = currentMediaMetadata.flatMapLatest { mediaMetadata ->
        database.song(mediaMetadata?.id)
    }.stateIn(offloadScope, SharingStarted.Lazily, null)

    private val currentFormat = currentMediaMetadata.flatMapLatest { mediaMetadata ->
        database.format(mediaMetadata?.id)
    }

    private val normalizeFactor = MutableStateFlow(1f)

    private val audioDecoder =
        dataStore.get(AudioDecoderKey, DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
    private val isGaplessOffloadAllowed = dataStore.get(AudioGaplessOffloadKey, false)
    val playerVolume = MutableStateFlow(dataStore.get(PlayerVolumeKey, 1f).coerceIn(0f, 1f))

    private var isAudioEffectSessionOpened = false

    var consecutivePlaybackErr = 0

    // ====== SISTEMA MEJORADO PARA MANEJO DE ERRORES 403 ======
    private val max403Retries = 3
    private val retryBackoffMs = longArrayOf(500L, 2000L, 5000L)
    // Almacenar: (URL, expiryTime, cacheTime)
    private val songUrlCache = HashMap<String, Triple<String, Long, Long>>()

    // Sistema de tokens para forzar refresh de URLs
    private val forceRefreshTokens = HashMap<String, String>()

    // Track URLs que han fallado recientemente
    private val failed403Urls = HashMap<String, Long>()
    private val url403CooldownMs = 300000L // 5 minutos
    
    // Timestamp de la última importación para invalidar URLs viejas
    private var lastImportTime: Long = 0

    /**
     * BroadcastReceiver para limpiar cache cuando se importa una base de datos
     */
    private val cacheCleanupReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.kraata.harmony.CLEAR_STREAMING_CACHE" -> {
                    Log.w(TAG, "🔥 LIMPIEZA DE CACHE SOLICITADA POR IMPORTACIÓN")
                    clearAllStreamingCaches()
                }
            }
        }
    }

    override fun onCreate() {
        Log.i(TAG, "Starting MusicService")
        super.onCreate()

        // Registrar receiver para limpieza de cache post-importación
        val filter = IntentFilter("com.kraata.harmony.CLEAR_STREAMING_CACHE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(cacheCleanupReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(cacheCleanupReceiver, filter)
        }
        Log.d(TAG, "Cache cleanup receiver registered")

        // Verificar si hubo una importación reciente
        checkForImportCompletedMarker()

        // init connectivityObserver early to avoid race with data source resolution
        try {
            connectivityObserver.unregister()
        } catch (e: UninitializedPropertyAccessException) {
            // ignore
        }
        connectivityObserver = NetworkConnectivityObserver(this)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(createDataSourceFactory()))
            .setRenderersFactory(createRenderersFactory(isGaplessOffloadAllowed))
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(), true
            )
            .setSeekBackIncrementMs(5000)
            .setSeekForwardIncrementMs(5000)
            .build()
            .apply {
                // listeners
                addListener(this@MusicService)
                sleepTimer = SleepTimer(scope, this)
                addListener(sleepTimer)
                addAnalyticsListener(PlaybackStatsListener(false, this@MusicService))

                // restore saved offload state
                setOffloadEnabled(dataStore.get(AudioOffloadKey, false))
            }

        mediaLibrarySessionCallback.apply {
            service = this@MusicService
            toggleLike = ::toggleLike
            toggleStartRadio = ::toggleStartRadio
            toggleLibrary = ::toggleLibrary
        }

        mediaSession = MediaLibrarySession.Builder(this, player, mediaLibrarySessionCallback)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setBitmapLoader(CoilBitmapLoader(this))
            .build()

        player.repeatMode = dataStore.get(RepeatModeKey, REPEAT_MODE_OFF)

        // Keep a connected controller so that notification works
        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({ controllerFuture.get() }, MoreExecutors.directExecutor())

        connectivityManager = getSystemService()!!

        // Update notification when currentSong changes
        currentSong.collect(scope) {
            updateNotification()
        }

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(
                this@MusicService,
                { NOTIFICATION_ID },
                CHANNEL_ID,
                R.string.music_player
            )
                .apply {
                    setSmallIcon(R.drawable.small_icon)
                }
        )

        // Offload scope tasks (IO-heavy operations live here)
        offloadScope.launch {
            Log.i(TAG, "Launching MusicService offloadScope tasks")

            if (!qbInit.value) {
                initQueue()
            }

            combine(playerVolume, normalizeFactor) { playerVolume, normalizeFactor ->
                playerVolume * normalizeFactor
            }.collectLatest(scope) {
                withContext(Dispatchers.Main) {
                    player.volume = it
                }
            }

            playerVolume.debounce(1000).collect(scope) { volume ->
                dataStore.edit { settings ->
                    settings[PlayerVolumeKey] = volume
                }
            }

            dataStore.data
                .map { it[SkipSilenceKey] ?: false }
                .distinctUntilChanged()
                .collectLatest(scope) {
                    withContext(Dispatchers.Main) {
                        player.skipSilenceEnabled = it
                    }
                }

            combine(
                currentFormat,
                dataStore.data
                    .map { it[AudioNormalizationKey] ?: true }
                    .distinctUntilChanged()
            ) { format, normalizeAudio ->
                format to normalizeAudio
            }.collectLatest(scope) { (format, normalizeAudio) ->
                normalizeFactor.value = if (normalizeAudio && format?.loudnessDb != null) {
                    min(10f.pow(-format.loudnessDb.toFloat() / 20), 1f)
                } else {
                    1f
                }
            }

            // network connectivity watcher
            offloadScope.launch {
                connectivityObserver.networkStatus.collect { isConnected ->
                    isNetworkConnected.value = isConnected

                    if (isConnected && waitingForNetworkConnection.value) {
                        waitingForNetworkConnection.value = false
                        withContext(Dispatchers.Main) {
                            player.prepare()
                            player.play()
                        }
                    }
                }
            }
        }
    }

    /**
     * Verifica si se completó una importación recientemente y limpia caches si es necesario
     */
    private fun checkForImportCompletedMarker() {
        try {
            val markerFile = File(cacheDir, ".import_completed_clear_cache")

            if (!markerFile.exists()) {
                Log.d(TAG, "No import marker found, skipping cache cleanup")
                return
            }

            val importTimeStr = markerFile.readText()
            val importTime = importTimeStr.toLongOrNull() ?: 0L
            val elapsedMs = System.currentTimeMillis() - importTime
            val elapsedMinutes = elapsedMs / (60 * 1000)

            // Si la importación fue hace menos de 5 minutos
            if (elapsedMs < 5 * 60 * 1000) {
                Log.w(TAG, "═══════════════════════════════════════════════════")
                Log.w(TAG, "⚠️ IMPORTACIÓN DETECTADA ($elapsedMinutes min ago)")
                Log.w(TAG, "🔥 LIMPIANDO TODOS LOS CACHES DE URLs...")
                Log.w(TAG, "═══════════════════════════════════════════════════")

                clearAllStreamingCaches()

                Log.i(TAG, "✅ Caches limpiados exitosamente")
            } else {
                Log.d(TAG, "Import marker too old (${elapsedMinutes} min), ignoring")
            }

            // Eliminar marcador
            if (markerFile.delete()) {
                Log.d(TAG, "✅ Import marker deleted")
            } else {
                Log.w(TAG, "⚠️ Could not delete import marker")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error checking import marker: ${e.message}", e)
        }
    }

    /**
     * Limpia todos los caches de URLs de streaming
     * Llamado cuando se detecta una importación reciente
     */
    private fun clearAllStreamingCaches() {
        try {
            // CRÍTICO: Establecer timestamp de importación PRIMERO
            lastImportTime = System.currentTimeMillis()
            Log.i(TAG, "📝 Marcando timestamp de importación: $lastImportTime")
            
            // 1. Limpiar cache de URLs en memoria
            val urlCacheSize = songUrlCache.size
            songUrlCache.clear()
            Log.i(TAG, "  ✅ songUrlCache limpiado: $urlCacheSize URLs eliminadas")

            // 2. Limpiar contadores y estados de reintentos
            val retry403Size = retry403Counts.size
            retry403Counts.clear()
            Log.i(TAG, "  ✅ retry403Counts limpiado: $retry403Size entradas")

            val failed403Size = failed403Urls.size
            failed403Urls.clear()
            Log.i(TAG, "  ✅ failed403Urls limpiado: $failed403Size entradas")

            val forceRefreshSize = forceRefreshTokens.size
            forceRefreshTokens.clear()
            Log.i(TAG, "  ✅ forceRefreshTokens limpiado: $forceRefreshSize tokens")

            // 3. Detener reproducción actual para forzar nueva carga
            scope.launch(Dispatchers.Main) {
                try {
                    val wasPlaying = player.isPlaying
                    val currentPosition = player.currentPosition
                    val currentIndex = player.currentMediaItemIndex

                    if (player.mediaItemCount > 0) {
                        Log.d(TAG, "  🔄 Reiniciando reproducción para aplicar limpieza...")

                        // Detener y limpiar
                        player.stop()
                        player.clearMediaItems()

                        // Si había algo reproduciéndose, recargar la cola
                        if (currentIndex >= 0 && wasPlaying) {
                            Log.d(TAG, "  🔄 Recargando cola desde posición $currentIndex...")

                            // Dar un momento para que se limpien los caches
                            delay(500)

                            // Recargar cola actual
                            queueBoard.setCurrQueuePosIndex(currentIndex)
                            queueBoard.setCurrQueue(shouldResume = false)

                            // Buscar posición
                            player.seekTo(currentIndex, currentPosition)

                            // Preparar pero NO reproducir automáticamente
                            player.prepare()

                            Log.i(TAG, "  ✅ Cola recargada, lista para reproducir")
                            Toast.makeText(
                                this@MusicService,
                                "Cache limpiado - presiona play para continuar",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Log.d(TAG, "  ℹ️ No había reproducción activa")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "  ❌ Error reiniciando reproducción: ${e.message}", e)
                }
            }

            // 4. Limpiar cache de ExoPlayer (opcional pero recomendado)
            offloadScope.launch(Dispatchers.IO) {
                try {
                    // Obtener todas las keys cacheadas
                    val cachedKeys = playerCache.keys.toList()
                    Log.d(TAG, "  🗑️ Limpiando ${cachedKeys.size} archivos de playerCache...")

                    var removed = 0
                    cachedKeys.forEach { key ->
                        try {
                            playerCache.removeResource(key)
                            removed++
                        } catch (e: Exception) {
                            // Ignorar errores individuales
                        }
                    }
                    Log.i(TAG, "  ✅ playerCache limpiado: $removed/${cachedKeys.size} archivos")
                } catch (e: Exception) {
                    Log.w(TAG, "  ⚠️ Error limpiando playerCache: ${e.message}")
                }
            }

            Log.i(TAG, "═══════════════════════════════════════════════════")
            Log.i(TAG, "✅ LIMPIEZA DE CACHES COMPLETADA")
            Log.i(TAG, "═══════════════════════════════════════════════════")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error durante limpieza de caches", e)
        }
    }


// Library functions

    private suspend fun recoverSong(
        mediaId: String,
        playbackData: YTPlayerUtils.PlaybackData? = null
    ) {
        val song = database.song(mediaId).first()
        val mediaMetadata = withContext(Dispatchers.Main) {
            player.findNextMediaItemById(mediaId)?.metadata
        } ?: return
        val duration = song?.song?.duration?.takeIf { it != -1 }
            ?: mediaMetadata.duration.takeIf { it != -1 }
            ?: (playbackData?.videoDetails ?: YTPlayerUtils.playerResponseForMetadata(mediaId)
                .getOrNull()?.videoDetails)?.lengthSeconds?.toInt()
            ?: -1
        database.query {
            if (song == null) insert(mediaMetadata.copy(duration = duration))
            else if (song.song.duration == -1) update(song.song.copy(duration = duration))
        }
        if (!database.hasRelatedSongs(mediaId)) {
            val relatedEndpoint =
                YouTube.next(WatchEndpoint(videoId = mediaId)).getOrNull()?.relatedEndpoint
                    ?: return
            val relatedPage = YouTube.related(relatedEndpoint).getOrNull() ?: return
            database.query {
                relatedPage.songs
                    .map(SongItem::toMediaMetadata)
                    .onEach(::insert)
                    .map {
                        RelatedSongMap(
                            songId = mediaId,
                            relatedSongId = it.id
                        )
                    }
                    .forEach(::insert)
            }
        }
    }

    fun toggleLibrary() {
        database.query {
            currentSong.value?.let {
                update(it.song.toggleLibrary())
            }
        }
    }

    fun toggleLike() {
        database.query {
            currentSong.value?.let {
                val song = it.song.toggleLike()
                update(song)

                if (!song.isLocal) {
                    syncUtils.likeSong(song)
                }
            }
        }
    }

    fun toggleStartRadio() {
        val mediaMetadata = player.currentMetadata ?: return
        playQueue(YouTubeQueue.radio(mediaMetadata), isRadio = true)
    }


// Queue

    /**
     * Play a queue.
     *
     * @param queue Queue to play.
     * @param playWhenReady
     * @param shouldResume Set to true for the player should resume playing at the current song's last save position or
     * false to start from the beginning.
     * @param replace Replace media items instead of the underlying logic
     * @param title Title override for the queue. If this value us unspecified, this method takes the value from queue.
     * If both are unspecified, the title will default to "Queue".
     */
    fun playQueue(
        queue: Queue,
        playWhenReady: Boolean = true,
        shouldResume: Boolean = false,
        replace: Boolean = false,
        isRadio: Boolean = false,
        title: String? = null
    ) {
        if (!qbInit.value) {
            runBlocking(Dispatchers.IO) {
                initQueue()
            }
        }

        var queueTitle = title
        queuePlaylistId = queue.playlistId
        var q: MultiQueueObject? = null
        val preloadItem = queue.preloadItem
        // do not use scope.launch ... it breaks randomly... why is this bug back???
        CoroutineScope(Dispatchers.Main).launch {
            Log.d(TAG, "playQueue: Resolving additional queue data...")
            try {
                if (preloadItem != null) {
                    q = queueBoard.addQueue(
                        queueTitle ?: "Radio\u2060temp",
                        listOf(preloadItem),
                        shuffled = queue.startShuffled,
                        replace = replace,
                        continuationEndpoint = null // fulfilled later on after initial status
                    )
                    queueBoard.setCurrQueue(q, true)
                }

                val initialStatus = withContext(Dispatchers.IO) { queue.getInitialStatus() }
                // do not find a title if an override is provided
                if ((title == null) && initialStatus.title != null) {
                    queueTitle = initialStatus.title

                    if (preloadItem != null && q != null) {
                        queueBoard.renameQueue(q!!, queueTitle)
                    }
                }

                val items = ArrayList<MediaMetadata>()
                Log.d(
                    TAG,
                    "playQueue: Queue initial status item count: ${initialStatus.items.size}"
                )
                if (!initialStatus.items.isEmpty()) {
                    if (preloadItem != null) {
                        items.add(preloadItem)
                        items.addAll(initialStatus.items.subList(1, initialStatus.items.size))
                    } else {
                        items.addAll(initialStatus.items)
                    }
                    val q = queueBoard.addQueue(
                        queueTitle ?: getString(R.string.queue),
                        items,
                        shuffled = queue.startShuffled,
                        startIndex = if (initialStatus.mediaItemIndex > 0) initialStatus.mediaItemIndex else 0,
                        replace = replace || preloadItem != null,
                        continuationEndpoint = if (isRadio) items.takeLast(4).shuffled()
                            .first().id else null // yq?.getContinuationEndpoint()
                    )
                    queueBoard.setCurrQueue(q, shouldResume)
                }

                player.prepare()
                player.playWhenReady = playWhenReady
            } catch (e: Exception) {
                reportException(e)
                Toast.makeText(this@MusicService, "plr: ${e.message}", Toast.LENGTH_LONG)
                    .show()
            }

            Log.d(TAG, "playQueue: Queue additional data resolution complete")
        }
    }

    /**
     * Add items to queue, right after current playing item
     */
    fun enqueueNext(items: List<MediaItem>) {
        scope.launch {
            if (!qbInit.value) {

                // when enqueuing next when player isn't active, play as a new song
                if (items.isNotEmpty()) {
                    playQueue(
                        ListQueue(
                            title = items.first().mediaMetadata.title.toString(),
                            items = items.mapNotNull { it.metadata }
                        )
                    )
                }
            } else {
                // enqueue next
                queueBoard.getCurrentQueue()?.let {
                    queueBoard.addSongsToQueue(
                        it,
                        player.currentMediaItemIndex + 1,
                        items.mapNotNull { it.metadata })
                }
            }
        }
    }

    /**
     * Add items to end of current queue
     */
    fun enqueueEnd(items: List<MediaItem>) {
        queueBoard.enqueueEnd(items.mapNotNull { it.metadata })
    }

    fun triggerShuffle() {
        val oldIndex = player.currentMediaItemIndex
        queueBoard.setCurrQueuePosIndex(oldIndex)
        val currentQueue = queueBoard.getCurrentQueue() ?: return

        // shuffle and update player playlist
        if (!currentQueue.shuffled) {
            queueBoard.shuffleCurrent()
        } else {
            queueBoard.unShuffleCurrent()
        }
        queueBoard.setCurrQueue()

        updateNotification()
    }

    suspend fun initQueue() {
        Log.i(TAG, "+initQueue()")
        val persistQueue = dataStore.get(PersistentQueueKey, true)
        val maxQueues = dataStore.get(MaxQueuesKey, 19)
        if (persistQueue) {
            queueBoard = QueueBoard(
                this,
                queueBoard.masterQueues,
                database.readQueue().toMutableList(),
                maxQueues
            )
        } else {
            queueBoard = QueueBoard(this, queueBoard.masterQueues, maxQueues = maxQueues)
        }
        Log.d(
            TAG,
            "Queue with $maxQueues queue limit. Persist queue = $persistQueue. Queues loaded = ${queueBoard.masterQueues.size}"
        )
        qbInit.value = true
        Log.i(TAG, "-initQueue()")
    }

    fun deInitQueue() {
        Log.i(TAG, "+deInitQueue()")
        val pos = player.currentPosition
        queueBoard.shutdown()
        if (dataStore.get(PersistentQueueKey, true)) {
            runBlocking(Dispatchers.IO) {
                saveQueueToDisk(pos)
            }
        }
        // do not replace the object. Can lead to entire queue being deleted even though it is supposed to be saved already
        qbInit.value = false
        Log.i(TAG, "-deInitQueue()")
    }

    suspend fun saveQueueToDisk(currentPosition: Long) {
        val data = queueBoard.getAllQueues()
        if (data.isNotEmpty()) {
            data.last().lastSongPos = currentPosition
            database.updateAllQueues(data)
        }
    }

    // Convenience overload similar to original behavior
    fun saveQueueToDisk() {
        val pos = player.currentPosition
        runBlocking {
            runBlocking(Dispatchers.IO) {
                offloadScope.launch {
                    saveQueueToDisk(pos)
                }.join()
            }
        }
    }


// Audio playback

    private fun openAudioEffectSession() {
        if (isAudioEffectSessionOpened) return
        isAudioEffectSessionOpened = true
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            }
        )
    }

    private fun closeAudioEffectSession() {
        if (!isAudioEffectSessionOpened) return
        isAudioEffectSessionOpened = false
        sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            }
        )
    }

    private fun createCacheDataSource(): CacheDataSource.Factory {
        return CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(
                CacheDataSource.Factory()
                    .setCache(playerCache)
                    .setUpstreamDataSourceFactory(
                        DefaultDataSource.Factory(
                            this,
                            OkHttpDataSource.Factory(
                                OkHttpClient.Builder()
                                    .proxy(YouTube.proxy)
                                    .build()
                            )
                        )
                    )
                    .setCacheWriteDataSinkFactory(
                        HybridCacheDataSinkFactory(playerCache) { dataSpec ->
                            val isLocal = queueBoard.getCurrentQueue()
                                ?.findSong(dataSpec.key ?: "")?.isLocal == true
                            Log.d(TAG, "SONG CACHE: ${!isLocal}")
                            !isLocal
                        }
                    )
                    .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)
            )
            .setCacheWriteDataSinkFactory(null)
            .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private fun createDataSourceFactory(): DataSource.Factory {
        return ResolvingDataSource.Factory(createCacheDataSource()) { dataSpec ->
            val mediaId = dataSpec.key ?: error("No media id")
            Log.d(TAG, "PLAYING: song id = $mediaId")

            var song = queueBoard.getCurrentQueue()?.findSong(dataSpec.key ?: "")
            if (song == null) { // in the case of resumption, queueBoard may not be ready yet
                song = runBlocking { database.song(dataSpec.key).first()?.toMediaMetadata() }
            }

            // local song
            if (song?.localPath != null) {
                if (song.isLocal) {
                    Log.d(TAG, "PLAYING: local song")
                    val file = File(song.localPath)
                    if (!file.exists()) {
                        throw PlaybackException(
                            "File not found",
                            Throwable(),
                            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                        )
                    }

                    return@Factory dataSpec.withUri(file.toUri())
                } else {
                    val isDownloadNew = downloadUtil.localMgr.getFilePathIfExists(mediaId)
                    isDownloadNew?.let {
                        Log.d(TAG, "PLAYING: Custom downloaded song")
                        return@Factory dataSpec.withUri(it)
                    }
                }
            }

            val isDownload =
                downloadCache.isCached(
                    mediaId,
                    dataSpec.position,
                    if (dataSpec.length >= 0) dataSpec.length else 1
                )
            val isCache = playerCache.isCached(mediaId, dataSpec.position, CHUNK_LENGTH)
            if (isDownload || isCache) {
                Log.d(TAG, "PLAYING: remote song (cache = ${isCache}, download = ${isDownload})")
                offloadScope.launch { recoverSong(mediaId) }
                return@Factory dataSpec
            }

            // ====== SISTEMA MEJORADO: Verificar si debemos forzar refresh ======
            val forceRefresh = forceRefreshTokens.containsKey(mediaId)
            if (forceRefresh) {
                Log.d(TAG, "PLAYING: Force refresh requested for $mediaId")
                forceRefreshTokens.remove(mediaId)
            }

            // ====== Verificar si la URL en cache está expirada ======
            val cachedUrl = songUrlCache[mediaId]
            val safetyWindowMs = 30_000L // Aumentado a 30 segundos

            // Si hay force refresh, ignoramos la cache por completo
            if (!forceRefresh && cachedUrl != null) {
                val (url, expiryTime, cacheTime) = cachedUrl
                val now = System.currentTimeMillis()
                
                // Verificar si la URL fue cacheada ANTES de la última importación
                val wasUrlCachedBeforeImport = cacheTime < lastImportTime
                
                Log.d(TAG, "PLAYING: Cache validation for $mediaId:")
                Log.d(TAG, "  - expiryTime: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(expiryTime))}")
                Log.d(TAG, "  - now: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))}")
                Log.d(TAG, "  - cacheTime: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(cacheTime))}")
                Log.d(TAG, "  - lastImportTime: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(lastImportTime))}")
                Log.d(TAG, "  - wasUrlCachedBeforeImport: $wasUrlCachedBeforeImport (cacheTime=$cacheTime < lastImport=$lastImportTime)")
                Log.d(TAG, "  - isExpired: ${expiryTime - safetyWindowMs < now}")
                
                if (expiryTime - safetyWindowMs > now && !wasUrlCachedBeforeImport) {
                    val urlPreview = if (url.length > 100) url.substring(0, 100) + "..." else url
                    Log.d(
                        TAG,
                        "PLAYING: remote song (URL cache HIT, expires in ${(expiryTime - now) / 1000}s)"
                    )
                    Log.d(TAG, "PLAYING: Using cached URL for $mediaId: $urlPreview")
                    offloadScope.launch { recoverSong(mediaId) }
                    return@Factory dataSpec.withUri(url.toUri()).subrange(dataSpec.uriPositionOffset, CHUNK_LENGTH)
                } else {
                    val reason = when {
                        expiryTime - safetyWindowMs < now -> "EXPIRED (${(expiryTime - now) / 1000}s)"
                        wasUrlCachedBeforeImport -> "POST-IMPORT (potentially poisoned)"
                        else -> "UNKNOWN"
                    }
                    Log.w(TAG, "PLAYING: URL cache INVALID for $mediaId - reason: $reason")
                    songUrlCache.remove(mediaId)
                    failed403Urls.remove(mediaId) // Reset cooldown también si invalidamos
                }
            } else if (cachedUrl == null) {
                Log.d(TAG, "PLAYING: No cached URL for $mediaId")
            } else if (forceRefresh) {
                Log.d(TAG, "PLAYING: Force refresh requested for $mediaId, ignoring cache")
            }

            // ====== Verificar cooldown de URLs fallidas ======
            failed403Urls[mediaId]?.let { failTime ->
                val cooldownRemaining = url403CooldownMs - (System.currentTimeMillis() - failTime)
                if (cooldownRemaining > 0) {
                    Log.w(
                        TAG,
                        "PLAYING: URL for $mediaId failed recently, cooldown ${cooldownRemaining / 1000}s remaining"
                    )
                    throw PlaybackException(
                        "URL recently failed with 403, waiting for cooldown",
                        Throwable(),
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                    )
                } else {
                    // Cooldown expired, remove from failed list
                    failed403Urls.remove(mediaId)
                    Log.i(TAG, "PLAYING: Cooldown expired for $mediaId, retrying...")
                }
            }

            Log.d(TAG, "PLAYING: remote song (online fetch${if (forceRefresh) " - forced refresh" else ""})")

            val playbackData = runBlocking(Dispatchers.IO) {
                val audioQuality by enumPreference(
                    this@MusicService,
                    AudioQualityKey,
                    AudioQuality.AUTO
                )
                YTPlayerUtils.playerResponseForPlayback(
                    mediaId,
                    audioQuality = audioQuality,
                    connectivityManager = connectivityManager,
                )
            }.getOrElse { throwable ->
                // Si hay error, asegurarnos de limpiar forceRefreshTokens
                forceRefreshTokens.remove(mediaId)
                when (throwable) {
                    is PlaybackException -> throw throwable

                    is ConnectException, is UnknownHostException -> {
                        throw PlaybackException(
                            getString(R.string.error_no_internet),
                            throwable,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                        )
                    }

                    is SocketTimeoutException -> {
                        throw PlaybackException(
                            getString(R.string.error_timeout),
                            throwable,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                        )
                    }

                    else -> throw PlaybackException(
                        getString(R.string.error_unknown),
                        throwable,
                        PlaybackException.ERROR_CODE_REMOTE_ERROR
                    )
                }
            }
            val format = playbackData.format

            database.query {
                upsert(
                    FormatEntity(
                        id = mediaId,
                        itag = format.itag,
                        mimeType = format.mimeType.split(";")[0],
                        codecs = format.mimeType.split("codecs=")[1].removeSurrounding("\""),
                        bitrate = format.bitrate,
                        sampleRate = format.audioSampleRate,
                        contentLength = format.contentLength!!,
                        loudnessDb = playbackData.audioConfig?.loudnessDb,
                        playbackTrackingUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                    )
                )
            }
            offloadScope.launch { recoverSong(mediaId, playbackData) }

            val streamUrl = playbackData.streamUrl
            val expiryMs = System.currentTimeMillis() + (playbackData.streamExpiresInSeconds * 1000L)

            // ====== MEJORA: Reducir tiempo de cache si hubo problemas previos ======
            val finalExpiryMs = if (failed403Urls.containsKey(mediaId)) {
                // Para URLs problemáticas, cachear por menos tiempo (max 10 minutos)
                System.currentTimeMillis() + min(playbackData.streamExpiresInSeconds * 1000L, 600_000L)
            } else {
                expiryMs
            }

            val expiryTimeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(Date(finalExpiryMs))
            Log.i(
                TAG,
                "PLAYING: Fetched new URL for $mediaId, expires at $expiryTimeStr (in ${(finalExpiryMs - System.currentTimeMillis()) / 1000}s)"
            )
            
            // Log de la nueva URL obtenida
            val urlPreview = if (streamUrl.length > 100) streamUrl.substring(0, 100) + "..." else streamUrl
            Log.d(TAG, "PLAYING: New URL fetched for $mediaId: $urlPreview")

            // Almacenar: URL, expiry time, y timestamp de cuándo se cacheó
            val cacheTime = System.currentTimeMillis()
            Log.d(TAG, "PLAYING: Storing URL in cache - mediaId=$mediaId, cacheTime=${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(cacheTime))}, lastImportTime=${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(lastImportTime))}")
            songUrlCache[mediaId] = Triple(streamUrl, finalExpiryMs, cacheTime)
            dataSpec.withUri(streamUrl.toUri()).subrange(dataSpec.uriPositionOffset, CHUNK_LENGTH)
        }
    }

    private fun createRenderersFactory(gaplessOffloadAllowed: Boolean): DefaultRenderersFactory {
        if (ENABLE_FFMETADATAEX) {
            return object : NextRenderersFactory(this@MusicService) {
                override fun buildAudioSink(
                    context: Context,
                    pcmEncodingRestrictionLifted: Boolean,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink? {
                    return DefaultAudioSink.Builder(this@MusicService)
                        .setPcmEncodingRestrictionLifted(pcmEncodingRestrictionLifted)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessorChain(
                            DefaultAudioSink.DefaultAudioProcessorChain(
                                emptyArray(),
                                SilenceSkippingAudioProcessor(),
                                SonicAudioProcessor()
                            )
                        )
                        .setAudioOffloadSupportProvider(
                            if (!gaplessOffloadAllowed) OtOffloadSupportProvider(
                                context
                            ) else DefaultAudioOffloadSupportProvider(context)
                        )
                        .build()
                }
            }
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(audioDecoder)
        } else {
            return object : DefaultRenderersFactory(this) {
                override fun buildAudioSink(
                    context: Context,
                    pcmEncodingRestrictionLifted: Boolean,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink? {
                    return DefaultAudioSink.Builder(this@MusicService)
                        .setPcmEncodingRestrictionLifted(pcmEncodingRestrictionLifted)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessorChain(
                            DefaultAudioSink.DefaultAudioProcessorChain(
                                emptyArray(),
                                SilenceSkippingAudioProcessor(),
                                SonicAudioProcessor()
                            )
                        )
                        .setAudioOffloadSupportProvider(
                            if (!gaplessOffloadAllowed) OtOffloadSupportProvider(
                                context
                            ) else DefaultAudioOffloadSupportProvider(context)
                        )
                        .build()
                }
            }
        }
    }


// Misc

    fun updateNotification() {
        mediaSession.setCustomLayout(
            listOf(
                CommandButton.Builder(ICON_UNDEFINED)
                    .setDisplayName(getString(if (queueBoard.getCurrentQueue()?.shuffled == true) R.string.action_shuffle_off else R.string.action_shuffle_on))
                    .setSessionCommand(CommandToggleShuffle)
                    .setCustomIconResId(if (player.shuffleModeEnabled) R.drawable.shuffle_on else R.drawable.shuffle_off)
                    .build(),
                CommandButton.Builder(ICON_UNDEFINED)
                    .setDisplayName(
                        getString(
                            when (player.repeatMode) {
                                REPEAT_MODE_OFF -> R.string.repeat_mode_off
                                REPEAT_MODE_ONE -> R.string.repeat_mode_one
                                REPEAT_MODE_ALL -> R.string.repeat_mode_all
                                else -> throw IllegalStateException()
                            }
                        )
                    )
                    .setCustomIconResId(
                        when (player.repeatMode) {
                            REPEAT_MODE_OFF -> R.drawable.repeat_off
                            REPEAT_MODE_ONE -> R.drawable.repeat_one
                            REPEAT_MODE_ALL -> R.drawable.repeat_on
                            else -> throw IllegalStateException()
                        }
                    )
                    .setSessionCommand(CommandToggleRepeatMode)
                    .build(),
                CommandButton.Builder(if (currentSong.value?.song?.liked == true) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                    .setDisplayName(getString(if (currentSong.value?.song?.liked == true) R.string.action_remove_like else R.string.action_like))
                    .setSessionCommand(CommandToggleLike)
                    .setEnabled(currentSong.value != null)
                    .build(),
                CommandButton.Builder(CommandButton.ICON_RADIO)
                    .setDisplayName(getString(R.string.start_radio))
                    .setSessionCommand(CommandToggleStartRadio)
                    .setEnabled(currentSong.value != null)
                    .build()
            )
        )
    }

    fun waitOnNetworkError() {
        waitingForNetworkConnection.value = true
        Toast.makeText(this@MusicService, getString(R.string.wait_to_reconnect), Toast.LENGTH_LONG)
            .show()
    }

    fun skipOnError() {
        /**
         * Auto skip to the next media item on error.
         *
         * To prevent a "runaway diesel engine" scenario, force the user to take action after
         * too many errors come up too quickly. Pause to show player "stopped" state
         */
        consecutivePlaybackErr += 2
        val nextWindowIndex = player.nextMediaItemIndex

        if (consecutivePlaybackErr <= MAX_PLAYER_CONSECUTIVE_ERR && nextWindowIndex != C.INDEX_UNSET) {
            player.seekTo(nextWindowIndex, C.TIME_UNSET)
            player.prepare()
            player.play()

            Toast.makeText(
                this@MusicService,
                getString(R.string.err_play_next_on_error),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        player.pause()
        Toast.makeText(
            this@MusicService,
            getString(R.string.err_stop_on_too_many_errors),
            Toast.LENGTH_LONG
        ).show()
        consecutivePlaybackErr = 0
    }

    fun stopOnError() {
        player.pause()
        Toast.makeText(this@MusicService, getString(R.string.err_stop_on_error), Toast.LENGTH_LONG)
            .show()
    }

    // ====== SISTEMA MEJORADO DE REINTENTOS PARA 403 ======
    private val retry403Counts = HashMap<String, Int>()

    private fun retryWith403Backoff(mediaId: String) {
        val mediaRetry = retry403Counts.getOrDefault(mediaId, 0)
        if (mediaRetry >= max403Retries) {
            Log.e(TAG, "403 ERROR: Max retries ($max403Retries) reached for $mediaId")
            Toast.makeText(
                this@MusicService,
                "URL expired, max retries reached. Skipping...",
                Toast.LENGTH_LONG
            ).show()
            failed403Urls[mediaId] = System.currentTimeMillis()
            retry403Counts.remove(mediaId)
            forceRefreshTokens.remove(mediaId)
            skipOnError()
            return
        }

        val backoffDelay = retryBackoffMs.getOrElse(mediaRetry) { 5000L }
        Log.w(
            TAG,
            "403 ERROR: Retry ${mediaRetry + 1}/$max403Retries for $mediaId in ${backoffDelay}ms"
        )

        Toast.makeText(
            this@MusicService,
            "URL expired, retrying in ${backoffDelay / 1000}s... (${mediaRetry + 1}/$max403Retries)",
            Toast.LENGTH_SHORT
        ).show()

        scope.launch {
            delay(backoffDelay)

            // PASO CRÍTICO: Agregar token para forzar refresh
            forceRefreshTokens[mediaId] = "retry_${System.currentTimeMillis()}"

            // También invalidar caches
            invalidateMediaSync(mediaId)

            withContext(Dispatchers.Main) {
                val index = player.currentMediaItemIndex
                val position = player.currentPosition

                try {
                    // Método más robusto: detener y re-preparar
                    player.stop()

                    queueBoard.setCurrQueuePosIndex(index)

                    queueBoard.setCurrQueue(shouldResume = true)

                    player.seekTo(index, position)

                    player.prepare()
                    player.play()


                } catch (e: Exception) {
                    Log.e(TAG, "retryWith403Backoff: error while retrying playback", e)
                    // fallback: skip
                    skipOnError()
                }
            }

            // incrementar contador
            retry403Counts[mediaId] = mediaRetry + 1
        }
    }

// Player overrides

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)

        val currentMediaId = player.currentMediaItem?.mediaId

        // ====== Detección específica de errores 403 ======
        val is403Error = error.cause?.cause is HttpDataSource.InvalidResponseCodeException &&
                (error.cause?.cause as HttpDataSource.InvalidResponseCodeException).responseCode == 403

        if (is403Error && currentMediaId != null) {
            val now = System.currentTimeMillis()
            
            Log.e(TAG, "════════════════════════════════════════════════════════")
            Log.e(TAG, "❌ 403 ERROR DETECTED for media $currentMediaId")
            Log.e(TAG, "   Current time: ${SimpleDateFormat("HH:mm:ss.SSS").format(Date(now))}")
            
            // Log del estado del cache en el momento del error
            val cacheEntry = songUrlCache[currentMediaId]
            if (cacheEntry != null) {
                val (_, expiryTime, cacheTime) = cacheEntry
                val cacheAgeMs = now - cacheTime
                val expiryAgeMs = expiryTime - now
                
                Log.e(TAG, "CACHE STATE at error time:")
                Log.e(TAG, "   - URL was cached at: ${SimpleDateFormat("HH:mm:ss.SSS").format(Date(cacheTime))}")
                Log.e(TAG, "   - Cache age: ${cacheAgeMs}ms (${cacheAgeMs / 1000}s)")
                Log.e(TAG, "   - URL expiry: ${SimpleDateFormat("HH:mm:ss.SSS").format(Date(expiryTime))}")
                Log.e(TAG, "   - Time until expiry: ${expiryAgeMs}ms (${expiryAgeMs / 1000}s)")
                Log.e(TAG, "   - lastImportTime: ${SimpleDateFormat("HH:mm:ss.SSS").format(Date(lastImportTime))}")
                Log.e(TAG, "   - Was cached BEFORE import: ${cacheTime < lastImportTime}")
            } else {
                Log.e(TAG, "   - URL NOT in cache (was probably just fetched)")
            }
            
            // Log del estado de reintentos
            val retryCount = retry403Counts[currentMediaId] ?: 0
            Log.e(TAG, "RETRY STATE:")
            Log.e(TAG, "   - Retry count: $retryCount")
            Log.e(TAG, "   - Previously failed at: ${
                failed403Urls[currentMediaId]?.let { 
                    SimpleDateFormat("HH:mm:ss.SSS").format(Date(it)) 
                } ?: "Never"
            }")
            
            Log.e(TAG, "════════════════════════════════════════════════════════")

            // Marcar como fallida y forzar refresh
            failed403Urls[currentMediaId] = now
            forceRefreshTokens[currentMediaId] = "error_${now}"

            // Lanzar reintento
            retryWith403Backoff(currentMediaId)
            return
        }

        // wait for reconnection
        val isConnectionError = (error.cause?.cause is PlaybackException)
                && (error.cause?.cause as PlaybackException).errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        if (!isNetworkConnected.value || isConnectionError) {
            waitOnNetworkError()
            return
        }

        if (dataStore.get(SkipOnErrorKey, true)) {
            skipOnError()
        } else {
            stopOnError()
        }

        Toast.makeText(
            this@MusicService,
            "plr: ${error.message} (${error.errorCode}): ${error.cause?.message ?: "unknown"}",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun invalidateMedia(mediaId: String) {
        songUrlCache.remove(mediaId)
        failed403Urls.remove(mediaId)
        retry403Counts.remove(mediaId)
        forceRefreshTokens.remove(mediaId)

        Log.d(TAG, "invalidateMedia: cache cleared for $mediaId")
    }

    private suspend fun invalidateMediaSync(mediaId: String) {
        // Limpiar todos los caches en memoria
        songUrlCache.remove(mediaId)
        retry403Counts.remove(mediaId)

        // NOTA: No removemos de failed403Urls aquí - queremos mantener el cooldown
        // NOTA: No removemos forceRefreshTokens aquí - queremos que se use para forzar refresh

        withContext(Dispatchers.IO) {
            try {
                // Limpiar caches de ExoPlayer
                playerCache.removeResource(mediaId)
                downloadCache.removeResource(mediaId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove from cache: ${e.message}")
            }
        }

        Log.w(TAG, "invalidateMediaSync: complete for $mediaId")
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!isPlaying) {
            val pos = player.currentPosition
            val q = queueBoard.getCurrentQueue()
            q?.lastSongPos = pos
        }
        super.onIsPlayingChanged(isPlaying)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        super.onMediaItemTransition(mediaItem, reason)

        // ====== MEJORA: Reset retry counter en transición exitosa ======
        if (mediaItem != null && reason == MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            // Limpiar contadores de reintentos para la canción anterior
            mediaItem.mediaId?.let { mediaId ->
                retry403Counts.remove(mediaId)
                forceRefreshTokens.remove(mediaId)
            }
        }

        // +2 when and error happens, and -1 when transition. Thus when error, number increments by 1, else doesn't change
        if (consecutivePlaybackErr > 0) {
            consecutivePlaybackErr--
        }

        if (player.isPlaying && reason == MEDIA_ITEM_TRANSITION_REASON_SEEK) {
            player.prepare()
            player.play()
        }

        // Auto load more songs
        val q = queueBoard.getCurrentQueue()
        val songCount = q?.getSize() ?: -1
        val playlistId = q?.playlistId
        if (dataStore.get(AutoLoadMoreKey, true) &&
            reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
            player.mediaItemCount - player.currentMediaItemIndex <= 5 &&
            playlistId != null // aka "hasNext"
        ) {
            Log.d(TAG, "onMediaItemTransition: Triggering queue auto load more")
            scope.launch(SilentHandler) {
                val endpoint = playlistId // playlistId.substringBefore("\n")
                val continuation = null // playlistId.substringAfter("\n")
                val yq = YouTubeQueue(WatchEndpoint(endpoint, continuation))
                val mediaItems = yq.nextPage()
                q.playlistId =
                    mediaItems.takeLast(4).shuffled().first().id // yq.getContinuationEndpoint()
                Log.d(TAG, "onMediaItemTransition: Got ${mediaItems.size} songs from radio")
                if (player.playbackState != STATE_IDLE && songCount > 1) { // initial radio loading is handled by playQueue()
                    queueBoard.enqueueEnd(mediaItems.drop(1))
                }
            }
        }

        queueBoard.setCurrQueuePosIndex(player.currentMediaItemIndex)

        // reshuffle queue when shuffle AND repeat all are enabled
        // no, when repeat mode is on, player does not "STATE_ENDED"
        if (player.currentMediaItemIndex == player.mediaItemCount - 1 &&
            (reason == MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == MEDIA_ITEM_TRANSITION_REASON_SEEK) &&
            player.shuffleModeEnabled && player.repeatMode == REPEAT_MODE_ALL
        ) {
            scope.launch(SilentHandler) {
                // or else race condition: Assertions.checkArgument(eventTime.realtimeMs >= currentPlaybackStateStartTimeMs) fails in updatePlaybackState()
                delay(200)
                queueBoard.shuffleCurrent(player.mediaItemCount > 2)
                queueBoard.setCurrQueue()
            }
        }

        updateNotification() // also updates when queue changes
    }

    override fun onPlaybackStateChanged(@Player.State playbackState: Int) {
        if (playbackState == STATE_IDLE) {
            queuePlaylistId = null
        }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED
            )
        ) {
            val isBufferingOrReady =
                player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_READY
            if (isBufferingOrReady && player.playWhenReady) {
                openAudioEffectSession()
            } else {
                closeAudioEffectSession()
                if (!player.playWhenReady) {
                    waitingForNetworkConnection.value = false
                }
            }
        }
        if (events.containsAny(EVENT_TIMELINE_CHANGED, EVENT_POSITION_DISCONTINUITY)) {
            currentMediaMetadata.value = player.currentMetadata
        }
    }

    override fun onPlaybackStatsReady(
        eventTime: AnalyticsListener.EventTime,
        playbackStats: PlaybackStats
    ) {
        offloadScope.launch {
            val mediaItem =
                eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem
            var minPlaybackDur = (dataStore.get(minPlaybackDurKey, 30).toFloat() / 100)
            // ensure within bounds
            if (minPlaybackDur >= 1f) {
                minPlaybackDur = 0.99f // Ehhh 99 is good enough to avoid any rounding errors
            } else if (minPlaybackDur < 0.01f) {
                minPlaybackDur = 0.01f // Still want "spam skipping" to not count as plays
            }

            val playRatio =
                playbackStats.totalPlayTimeMs.toFloat() / ((mediaItem.metadata?.duration?.times(1000))
                    ?: -1)
            Log.d(TAG, "Playback ratio: $playRatio Min threshold: $minPlaybackDur")
            if (playRatio >= minPlaybackDur && !dataStore.get(PauseListenHistoryKey, false)) {
                database.query {
                    incrementPlayCount(mediaItem.mediaId)
                    try {
                        insert(
                            Event(
                                songId = mediaItem.mediaId,
                                timestamp = LocalDateTime.now(),
                                playTime = playbackStats.totalPlayTimeMs
                            )
                        )
                    } catch (_: SQLException) {
                    }
                }

                // TODO: support playlist id
                val ytHist = mediaItem.metadata?.isLocal != true && !dataStore.get(
                    PauseRemoteListenHistoryKey,
                    false
                )
                Log.d(TAG, "Trying to register remote history: $ytHist")
                if (ytHist) {
                    val playbackUrl =
                        YTPlayerUtils.playerResponseForMetadata(mediaItem.mediaId, null)
                            .getOrNull()?.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                    Log.d(TAG, "Got playback url: $playbackUrl")
                    playbackUrl?.let {
                        YouTube.registerPlayback(null, playbackUrl)
                            .onFailure {
                                reportException(it)
                            }
                    }
                }
            }
        }
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        updateNotification()
        offloadScope.launch {
            dataStore.edit { settings ->
                settings[RepeatModeKey] = repeatMode
            }
        }
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        val q = queueBoard.getCurrentQueue()
        player.setShuffleOrder(ShuffleOrder.UnshuffledShuffleOrder(player.mediaItemCount))
        if (q == null || q.shuffled == shuffleModeEnabled) return
        triggerShuffle()
    }


    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ) {
        // FG keep alive
        if (player.isPlaying || !dataStore.get(KeepAliveKey, false)) {
            super.onUpdateNotification(session, startInForegroundRequired)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "Terminating MusicService.")

        // Desregistrar receiver de limpieza de cache
        try {
            unregisterReceiver(cacheCleanupReceiver)
            Log.d(TAG, "Cache cleanup receiver unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering cache cleanup receiver: ${e.message}")
        }

        deInitQueue()

        mediaSession.player.stop()
        mediaSession.release()
        mediaSession.player.release()
        super.onDestroy()
        Log.i(TAG, "Terminated MusicService.")
    }

    override fun onBind(intent: Intent?) = super.onBind(intent) ?: binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "onTaskRemoved called")
        if (dataStore.get(StopMusicOnTaskClearKey, true) && !dataStore.get(KeepAliveKey, false)) {
            Log.i(TAG, "onTaskRemoved kill")
            pauseAllPlayersAndStopSelf()
        } else {
            Log.i(TAG, "onTaskRemoved def")
            super.onTaskRemoved(rootIntent)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    inner class MusicBinder : Binder() {
        val service: MusicService
            get() = this@MusicService
    }

    companion object {
        const val ROOT = "root"
        const val SONG = "song"
        const val ARTIST = "artist"
        const val ALBUM = "album"
        const val PLAYLIST = "playlist"
        const val SEARCH = "search"

        const val CHANNEL_ID = "music_channel_01"
        const val CHANNEL_NAME = "fgs_workaround"
        const val NOTIFICATION_ID = 888
        const val ERROR_CODE_NO_STREAM = 1000001
        const val CHUNK_LENGTH = 512 * 1024L

        const val COMMAND_GET_BINDER = "GET_BINDER"
    }
}
