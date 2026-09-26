package com.kraata.harmony.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.graphics.scale
import androidx.documentfile.provider.DocumentFile
import com.harmony.music.identifier.AudioFingerprint
import com.harmony.music.identifier.AudioSample
import com.harmony.music.identifier.MusicIdentificationResult
import com.harmony.music.identifier.MusicIdentifierService
import com.harmony.music.identifier.NativeNowPlayingMatch
import com.harmony.music.identifier.NativeNowPlayingMatcher
import com.kraata.harmony.BuildConfig
import com.kraata.harmony.db.MusicDatabase
import com.kraata.harmony.db.entities.AlbumArtistMap
import com.kraata.harmony.db.entities.AlbumEntity
import com.kraata.harmony.db.entities.ArtistEntity
import com.kraata.harmony.db.entities.GenreEntity
import com.kraata.harmony.db.entities.Song
import com.kraata.harmony.db.entities.SongAlbumMap
import com.kraata.harmony.db.entities.SongArtistMap
import com.kraata.harmony.db.entities.SongGenreMap
import com.kraata.harmony.constants.ScanPathsKey
import com.kyant.taglib.Picture
import com.kyant.taglib.TagLib
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.kraata.harmony.utils.dataStore
import com.kraata.harmony.utils.get
import com.kraata.harmony.utils.scanners.absoluteFilePathFromUri
import com.kraata.harmony.utils.scanners.documentFileFromUri
import com.kraata.harmony.utils.scanners.uriListFromString

internal sealed interface LocalSongMetadataUpdateResult {
    data object NoMatch : LocalSongMetadataUpdateResult

    data class LowConfidence(val score: Double) : LocalSongMetadataUpdateResult

    data class Updated(
        val artist: String,
        val title: String,
        val artworkMimeType: String?,
    ) : LocalSongMetadataUpdateResult
}

internal class LocalSongMetadataUpdater(
    private val database: MusicDatabase,
    private val context: Context,
    private val identifier: MusicIdentifierService = MusicIdentifierService(),
    private val acoustIdClient: AcoustIdClient = AcoustIdClient(),
    private val metadataClient: MusicMetadataClient = MusicMetadataClient(),
) {
    suspend fun update(song: Song): LocalSongMetadataUpdateResult = withContext(Dispatchers.IO) {
        require(song.song.isLocal) { "Only local songs can be updated" }
        val file = File(song.song.localPath ?: error("Local audio path is missing"))
        require(file.isFile && file.canRead()) { "Local audio file cannot be read" }
        debug("start file=${file.name}")

        val identification = identify(file)
        val fingerprint = identification.fingerprint
        debug("fingerprint bytes=${fingerprint.byteCount} duration=${fingerprint.durationMs}ms")
        val nativeNowPlayingMatch = findNativeNowPlayingMatch(identification.sample)
        val nativeMetadata = nativeNowPlayingMatch?.let { nativeMetadata(it) }
        val resolution: Pair<AcoustIdMatch?, MusicBrainzMetadata> = if (nativeMetadata != null) {
            null to nativeMetadata
        } else {
            val match = acoustIdClient.lookup(
                fingerprint = fingerprint.encoded,
                durationMs = fingerprint.durationMs,
                fileNameHint = file.name,
            ) ?: run {
                debug("no AcoustID match")
                return@withContext LocalSongMetadataUpdateResult.NoMatch
            }
            debug("match score=${match.score} recording=${match.recordingId}")

            if (match.score < MIN_CONFIDENCE) {
                return@withContext LocalSongMetadataUpdateResult.LowConfidence(match.score)
            }

            val metadata = try {
                metadataClient.lookup(match.recordingId)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                debug("MusicBrainz unavailable; using AcoustID metadata: ${exception.message}")
                match.toFallbackMetadata()
            }
            match to metadata
        }
        val match = resolution.first
        val metadata = resolution.second
        debug("musicbrainz title=${metadata.title} release=${metadata.releaseId}")
        val artwork = metadataClient.downloadCover(metadata)
        debug("cover downloaded=${artwork != null}")
        val artworkMimeType = openWritableDescriptor(file).use { descriptor ->
            saveMetadata(descriptor, file, match, fingerprint, metadata, artwork)
        }
        debug("file saved artwork=$artworkMimeType")
        updateDatabase(song, file, metadata)
        debug("database update scheduled")

        LocalSongMetadataUpdateResult.Updated(
            artist = metadata.artists.joinToString(", "),
            title = metadata.title,
            artworkMimeType = artworkMimeType,
        )
    }

    suspend fun updateManually(
        song: Song,
        title: String,
        artist: String,
        artworkBytes: ByteArray?,
        removeArtwork: Boolean,
    ) = withContext(Dispatchers.IO) {
        require(song.song.isLocal) { "Only local songs can be edited" }
        val normalizedTitle = title.trim()
        val normalizedArtist = artist.trim()
        require(normalizedTitle.isNotEmpty()) { "Song title cannot be empty" }
        require(normalizedArtist.isNotEmpty()) { "Song artist cannot be empty" }

        val file = File(song.song.localPath ?: error("Local audio path is missing"))
        require(file.isFile && file.canRead()) { "Local audio file cannot be read" }

        openWritableDescriptor(file).use { descriptor ->
            saveManualMetadata(
                descriptor = descriptor,
                file = file,
                title = normalizedTitle,
                artist = normalizedArtist,
                artworkBytes = artworkBytes,
                removeArtwork = removeArtwork,
            )
        }
        updateManualDatabase(song, file, normalizedTitle, normalizedArtist)
    }

    private fun openWritableDescriptor(file: File): ParcelFileDescriptor {
        runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
        }.getOrNull()?.let {
            debug("using direct file write access")
            return it
        }

        val roots = uriListFromString(context.dataStore.get(ScanPathsKey, ""))
            .mapNotNull { uri ->
                absoluteFilePathFromUri(context, uri)?.let { rootPath -> rootPath to uri }
            }
            .filter { (rootPath, _) ->
                file.absolutePath == rootPath ||
                    file.absolutePath.startsWith(rootPath.trimEnd('/') + File.separator)
            }
            .maxByOrNull { (rootPath, _) -> rootPath.length }
            ?: error("Local audio file is read-only or has no writable scan-folder permission")

        val (rootPath, rootUri) = roots
        var document: DocumentFile = documentFileFromUri(context, rootUri)
            ?: error("Could not access the writable scan folder")
        val relativePath = file.absolutePath.removePrefix(rootPath.trimEnd('/') + File.separator)
        if (relativePath == file.absolutePath) {
            error("Could not resolve the local audio file in the writable scan folder")
        }
        relativePath.split(File.separatorChar)
            .filter(String::isNotEmpty)
            .forEach { name ->
                document = document.findFile(name)
                    ?: error("Could not resolve $name in the writable scan folder")
            }

        require(document.isFile && document.canWrite()) { "Local audio file is read-only" }
        debug("using SAF write access")
        return context.contentResolver.openFileDescriptor(document.uri, "rw")
            ?: error("Could not open the local audio file for writing")
    }

    private suspend fun findNativeNowPlayingMatch(sample: AudioSample): NativeNowPlayingMatch? {
        if (!NativeNowPlayingMatcher.isComponentInstalled(context, NativeNowPlayingMatcher.CORE_COMPONENT)) {
            debug("nativeNowPlaying unavailable: core is not installed; falling back to AcoustID")
            return null
        }

        val shards = NativeNowPlayingMatcher.SUPPORTED_SHARD_GROUPS.filter {
            NativeNowPlayingMatcher.isComponentInstalled(context, it)
        }
        if (shards.isEmpty()) {
            debug("nativeNowPlaying unavailable: no fingerprint shard is installed; falling back to AcoustID")
            return null
        }

        debug("nativeNowPlaying lookup started shards=${shards.joinToString()}")
        val nativeMatch = try {
            withContext(Dispatchers.Default) {
                NativeNowPlayingMatcher.recognize(context, sample, shards)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            debug("nativeNowPlaying failed: ${exception.message}; falling back to AcoustID")
            null
        }

        if (nativeMatch == null) {
            debug("nativeNowPlaying returned no match; falling back to AcoustID")
            return null
        }
        debug(
            "nativeNowPlaying matched title=${nativeMatch.title} " +
                "artist=${nativeMatch.artist} googleId=${nativeMatch.googleId}",
        )
        return nativeMatch
    }

    private suspend fun nativeMetadata(nativeMatch: NativeNowPlayingMatch): MusicBrainzMetadata? {
        return try {
            metadataClient.lookupByTitleAndArtist(nativeMatch.title, nativeMatch.artist)
                ?.also { metadata ->
                    debug("nativeNowPlaying using MusicBrainz recording=${metadata.recordingId}")
                }
                ?: run {
                    debug("nativeNowPlaying MusicBrainz returned no match; falling back to AcoustID")
                    null
                }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            debug("nativeNowPlaying MusicBrainz failed: ${exception.message}; falling back to AcoustID")
            null
        }
    }

    private suspend fun identify(file: File): MusicIdentificationResult.Success {
        return when (val result = identifier.identifyFromFile(file)) {
            is MusicIdentificationResult.Success -> result
            MusicIdentificationResult.NoAudio -> error("The file contains no audio")
            is MusicIdentificationResult.PermissionMissing -> error("${result.permission} is required")
            is MusicIdentificationResult.ProcessingError -> error(result.message)
            is MusicIdentificationResult.UnsupportedFormat -> error(result.reason)
        }
    }

    @Suppress("DEPRECATION")
    private fun saveMetadata(
        descriptor: ParcelFileDescriptor,
        file: File,
        match: AcoustIdMatch?,
        fingerprint: AudioFingerprint,
        metadata: MusicBrainzMetadata,
        artworkBytes: ByteArray?,
    ): String? {
        val preferredArtwork = artworkBytes?.let { compressArtwork(it, file, forceJpeg = false) }

        val current = TagLib.getMetadata(
            fd = descriptor.dup().detachFd(),
            readPictures = true,
        ) ?: error("Could not read local metadata")

        val properties = HashMap(current.propertyMap)
        setProperty(properties, "TITLE", listOf(metadata.title))
        setProperty(properties, "ARTIST", metadata.artists)
        setProperty(properties, "ALBUM", listOfNotNull(metadata.album))
        setProperty(properties, "ALBUMARTIST", metadata.albumArtists)
        setProperty(properties, "GENRE", metadata.genres)
        setProperty(properties, "DATE", listOfNotNull(metadata.date))
        setProperty(properties, "TRACKNUMBER", listOfNotNull(metadata.trackNumber?.toString()))
        setProperty(properties, "DISCNUMBER", listOfNotNull(metadata.discNumber?.toString()))
        setProperty(properties, "LABEL", listOfNotNull(metadata.label))
        setProperty(properties, "CATALOGNUMBER", listOfNotNull(metadata.catalogNumber))
        setProperty(properties, "BARCODE", listOfNotNull(metadata.barcode))
        setProperty(properties, "ISRC", metadata.isrcs)
        setProperty(properties, "MUSICBRAINZ_TRACKID", listOf(metadata.recordingId))
        setProperty(properties, "MUSICBRAINZ_ARTISTID", metadata.artistIds)
        setProperty(properties, "MUSICBRAINZ_ALBUMID", listOfNotNull(metadata.releaseId))
        setProperty(properties, "MUSICBRAINZ_ALBUMARTISTID", metadata.albumArtistIds)
        setProperty(properties, "MUSICBRAINZ_RELEASEGROUPID", listOfNotNull(metadata.releaseGroupId))
        setProperty(properties, "RELEASECOUNTRY", listOfNotNull(metadata.releaseCountry))
        setProperty(properties, "RELEASESTATUS", listOfNotNull(metadata.releaseStatus))
        setProperty(properties, "RELEASETYPE", listOfNotNull(metadata.releaseType))
        setProperty(properties, "MEDIA", listOfNotNull(metadata.media))
        match?.let { acoustIdMatch ->
            setProperty(properties, "ACOUSTID_ID", listOf(acoustIdMatch.acoustId))
        }
        setProperty(properties, "ACOUSTID_FINGERPRINT", listOf(fingerprint.encoded))

        if (!TagLib.savePropertyMap(descriptor.dup().detachFd(), properties)) {
            error("Could not save local metadata")
        }

        var savedArtwork: Picture? = null
        val artworkPicture = preferredArtwork
        if (artworkPicture != null) {
            val pictures = current.pictures
                .filterNot { it.pictureType.equals("Front Cover", ignoreCase = true) }
                .toMutableList()
                .apply { add(0, artworkPicture) }

            if (TagLib.savePictures(descriptor.dup().detachFd(), pictures.toTypedArray())) {
                savedArtwork = artworkPicture
            } else if (artworkPicture.mimeType == MIME_WEBP) {
                val jpegArtwork = compressArtwork(artworkBytes, file, forceJpeg = true)
                if (jpegArtwork != null) {
                    val jpegPictures = current.pictures
                        .filterNot { it.pictureType.equals("Front Cover", ignoreCase = true) }
                        .toMutableList()
                        .apply { add(0, jpegArtwork) }

                    if (TagLib.savePictures(descriptor.dup().detachFd(), jpegPictures.toTypedArray())) {
                        savedArtwork = jpegArtwork
                    }
                }
            }
        }

        return savedArtwork?.mimeType
    }

    @Suppress("DEPRECATION")
    private fun saveManualMetadata(
        descriptor: ParcelFileDescriptor,
        file: File,
        title: String,
        artist: String,
        artworkBytes: ByteArray?,
        removeArtwork: Boolean,
    ) {
        debug("taglib begin op=getMetadata flow=manual readPictures=true")
        val current = TagLib.getMetadata(
            fd = descriptor.dup().detachFd(),
            readPictures = true,
        ) ?: error("Could not read local metadata")
        debug("taglib end op=getMetadata flow=manual pictureCount=${current.pictures.size}")

        val properties = HashMap(current.propertyMap)
        properties.keys.removeAll { key ->
            key.equals("TITLE", ignoreCase = true) ||
                key.equals("ARTIST", ignoreCase = true) ||
                key.equals("ARTISTS", ignoreCase = true)
        }
        properties["TITLE"] = arrayOf(title)
        properties["ARTIST"] = arrayOf(artist)

        debug("taglib begin op=savePropertyMap flow=manual")
        val propertiesSaved = TagLib.savePropertyMap(descriptor.dup().detachFd(), properties)
        debug("taglib end op=savePropertyMap flow=manual result=$propertiesSaved")
        if (!propertiesSaved) {
            error("Could not save local metadata")
        }

        if (artworkBytes == null && !removeArtwork) return

        val artworkPicture = artworkBytes?.let {
            compressArtwork(it, file, forceJpeg = false)
                ?: error("Could not decode cover art")
        }
        val pictures = current.pictures
            .filterNot { it.pictureType.equals("Front Cover", ignoreCase = true) }
            .toMutableList()
            .apply { artworkPicture?.let { add(0, it) } }

        debug(
            "taglib begin op=savePictures flow=manual variant=primary " +
                "pictureCount=${pictures.size} newMime=${artworkPicture?.mimeType ?: "none"} " +
                "newBytes=${artworkPicture?.data?.size ?: 0}",
        )
        val picturesSaved = TagLib.savePictures(descriptor.dup().detachFd(), pictures.toTypedArray())
        debug("taglib end op=savePictures flow=manual variant=primary result=$picturesSaved")
        if (picturesSaved) return

        if (artworkBytes != null && artworkPicture?.mimeType == MIME_WEBP) {
            val jpegArtwork = compressArtwork(artworkBytes, file, forceJpeg = true)
            if (jpegArtwork != null) {
                val jpegPictures = current.pictures
                    .filterNot { it.pictureType.equals("Front Cover", ignoreCase = true) }
                    .toMutableList()
                    .apply { add(0, jpegArtwork) }

                debug(
                    "taglib begin op=savePictures flow=manual variant=jpeg_fallback " +
                        "pictureCount=${jpegPictures.size} newMime=${jpegArtwork.mimeType} " +
                        "newBytes=${jpegArtwork.data.size}",
                )
                val jpegPicturesSaved = TagLib.savePictures(
                    descriptor.dup().detachFd(),
                    jpegPictures.toTypedArray(),
                )
                debug(
                    "taglib end op=savePictures flow=manual " +
                        "variant=jpeg_fallback result=$jpegPicturesSaved",
                )
                if (jpegPicturesSaved) return
            }
        }

        error("Could not save local cover art")
    }

    private fun compressArtwork(data: ByteArray, file: File, forceJpeg: Boolean): Picture? {
        debug("artwork_decode begin bytes=${data.size} forceJpeg=$forceJpeg")
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: run {
            debug("artwork_decode end result=null")
            return null
        }
        debug("artwork_decode end dimensions=${bitmap.width}x${bitmap.height}")
        val scaled = if (bitmap.width > MAX_ARTWORK_SIZE || bitmap.height > MAX_ARTWORK_SIZE) {
            val scale = minOf(
                MAX_ARTWORK_SIZE.toFloat() / bitmap.width,
                MAX_ARTWORK_SIZE.toFloat() / bitmap.height,
            )
            bitmap.scale(
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
            )
        } else {
            bitmap
        }
        if (scaled !== bitmap) {
            debug("artwork_scale from=${bitmap.width}x${bitmap.height} to=${scaled.width}x${scaled.height}")
        }

        val useJpeg = forceJpeg || file.extension.lowercase(Locale.ROOT) in JPEG_CONTAINERS
        val format = when {
            useJpeg -> Bitmap.CompressFormat.JPEG
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Bitmap.CompressFormat.WEBP_LOSSY
            else -> Bitmap.CompressFormat.WEBP
        }
        val output = ByteArrayOutputStream()
        debug(
            "artwork_compress begin format=${if (useJpeg) "jpeg" else "webp"} " +
                "quality=${if (useJpeg) JPEG_QUALITY else WEBP_QUALITY}",
        )
        return try {
            val compressed = scaled.compress(format, if (useJpeg) JPEG_QUALITY else WEBP_QUALITY, output)
            debug("artwork_compress end ok=$compressed outputBytes=${output.size()}")
            if (!compressed) {
                null
            } else {
                Picture(
                    data = output.toByteArray(),
                    description = "Front Cover",
                    pictureType = "Front Cover",
                    mimeType = if (useJpeg) MIME_JPEG else MIME_WEBP,
                )
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
    }

    private suspend fun updateDatabase(song: Song, file: File, metadata: MusicBrainzMetadata) {
        val oldArtistIds = song.artists.map(ArtistEntity::id)
        val oldGenreIds = song.genre.orEmpty().map(GenreEntity::id)
        val oldAlbumId = song.album?.id ?: song.song.albumId
        val releaseDate = metadata.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val year = releaseDate?.year ?: metadata.date?.take(4)?.toIntOrNull() ?: song.song.year
        val genreNames = metadata.genres.ifEmpty { song.genre.orEmpty().map(GenreEntity::title) }

        database.awaitTransaction {
            val artists = metadata.artists.distinct().map { name ->
                song.artists.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: artistsByNameFuzzy(name).firstOrNull {
                        it.isLocal && it.name.equals(name, ignoreCase = true)
                    }
                    ?: ArtistEntity(
                        id = ArtistEntity.generateArtistId(),
                        name = name,
                        isLocal = true,
                    )
            }
            val genres = genreNames.distinct().map { name ->
                song.genre.orEmpty().firstOrNull { it.title.equals(name, ignoreCase = true) }
                    ?: genreByNameFuzzy(name).firstOrNull {
                        it.isLocal && it.title.equals(name, ignoreCase = true)
                    }
                    ?: GenreEntity(
                        id = GenreEntity.generateGenreId(),
                        title = name,
                        isLocal = true,
                    )
            }
            val albumArtists = metadata.albumArtists.distinct().map { name ->
                artists.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: song.artists.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: artistsByNameFuzzy(name).firstOrNull {
                        it.isLocal && it.name.equals(name, ignoreCase = true)
                    }
                    ?: ArtistEntity(
                        id = ArtistEntity.generateArtistId(),
                        name = name,
                        isLocal = true,
                    )
            }
            val album = metadata.album?.let { albumName ->
                song.album?.takeIf { it.title.equals(albumName, ignoreCase = true) }
                    ?: localAlbumsByNameFuzzy(albumName).firstOrNull {
                        it.title.equals(albumName, ignoreCase = true)
                    }
                    ?: AlbumEntity(
                        id = AlbumEntity.generateAlbumId(),
                        title = albumName,
                        year = year,
                        thumbnailUrl = file.absolutePath,
                        songCount = 1,
                        duration = song.song.duration,
                        isLocal = true,
                    )
            } ?: song.album

            update(
                song.song.copy(
                    title = metadata.title,
                    thumbnailUrl = file.absolutePath,
                    localPath = file.absolutePath,
                    trackNumber = metadata.trackNumber ?: song.song.trackNumber,
                    discNumber = metadata.discNumber ?: song.song.discNumber,
                    albumId = album?.id,
                    albumName = album?.title,
                    year = year,
                    date = releaseDate?.atStartOfDay() ?: song.song.date,
                    dateModified = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(file.lastModified()),
                        ZoneOffset.UTC,
                    ),
                    isLocal = true,
                ),
            )

            unlinkSongArtists(song.id)
            unlinkSongAlbums(song.id)
            unlinkSongGenres(song.id)

            artists.forEachIndexed { index, artist ->
                insert(artist)
                insert(SongArtistMap(song.id, artist.id, index))
            }
            genres.forEachIndexed { index, genre ->
                insert(genre)
                insert(SongGenreMap(song.id, genre.id, index))
            }
            album?.let {
                upsert(it)
                unlinkAlbumArtists(it.id)
                insert(SongAlbumMap(song.id, it.id, 0))
                albumArtists.forEachIndexed { index, artist ->
                    insert(artist)
                    insert(AlbumArtistMap(it.id, artist.id, index))
                }
            }

            oldArtistIds.forEach(::safeDeleteArtist)
            oldGenreIds.forEach(::safeDeleteGenre)
            oldAlbumId?.let(::safeDeleteAlbum)
        }
    }

    private suspend fun updateManualDatabase(
        song: Song,
        file: File,
        title: String,
        artist: String,
    ) {
        val oldArtistIds = song.artists.map(ArtistEntity::id)
        database.awaitTransaction {
            val artistEntity = song.artists.firstOrNull {
                it.name.equals(artist, ignoreCase = true)
            } ?: artistsByNameFuzzy(artist).firstOrNull {
                it.isLocal && it.name.equals(artist, ignoreCase = true)
            } ?: ArtistEntity(
                id = ArtistEntity.generateArtistId(),
                name = artist,
                isLocal = true,
            )

            update(
                song.song.copy(
                    title = title,
                    thumbnailUrl = file.absolutePath,
                    localPath = file.absolutePath,
                    dateModified = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(file.lastModified()),
                        ZoneOffset.UTC,
                    ),
                    isLocal = true,
                ),
            )
            unlinkSongArtists(song.id)
            insert(artistEntity)
            insert(SongArtistMap(song.id, artistEntity.id, 0))
            oldArtistIds.forEach(::safeDeleteArtist)
        }
    }

    private companion object {
        const val TAG = "LocalSongMetadataUpdater"
        const val MIN_CONFIDENCE = 0.80
        const val MAX_ARTWORK_SIZE = 500
        const val WEBP_QUALITY = 82
        const val JPEG_QUALITY = 85
        const val MIME_WEBP = "image/webp"
        const val MIME_JPEG = "image/jpeg"
        val JPEG_CONTAINERS = setOf("m4a", "m4b", "mp4", "3gp", "3g2")
    }

    private fun debug(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}

internal fun AcoustIdMatch.toFallbackMetadata(): MusicBrainzMetadata = MusicBrainzMetadata(
    title = title,
    artists = artistNames,
    artistIds = emptyList(),
    album = null,
    albumArtists = emptyList(),
    albumArtistIds = emptyList(),
    genres = emptyList(),
    date = null,
    trackNumber = null,
    discNumber = null,
    label = null,
    catalogNumber = null,
    barcode = null,
    isrcs = emptyList(),
    recordingId = recordingId,
    releaseId = null,
    releaseGroupId = null,
    releaseCountry = null,
    releaseStatus = null,
    releaseType = null,
    media = null,
)

private fun setProperty(
    properties: HashMap<String, Array<String>>,
    key: String,
    values: List<String>,
) {
    if (values.isEmpty()) return
    properties.keys.removeAll { it.equals(key, ignoreCase = true) }
    properties[key] = values.toTypedArray()
}
