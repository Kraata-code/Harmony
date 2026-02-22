package com.kraata.harmony.viewmodels

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kraata.harmony.db.MusicDatabase
import com.kraata.harmony.db.entities.ArtistEntity
import com.kraata.harmony.db.entities.PlaylistEntity
import com.kraata.harmony.db.entities.PlaylistSongMap
import com.kraata.harmony.db.entities.SongArtistMap
import com.kraata.harmony.db.entities.SongEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class CrossForkMigrationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase
) : ViewModel() {

    companion object {
        const val TAG = "Migration"
        private const val CHUNK_SIZE = 300
        private const val PREVIEW_ROWS_COUNT = 5
        private const val SQLITE_MAX_BIND_ARGS = 900
    }

    private val _progress = MutableStateFlow<MigrateProgress>(MigrateProgress.Idle)
    val progress: StateFlow<MigrateProgress> = _progress

    private suspend fun cleanSessionDependentData() {
        try {
            val writable = database.openHelper.writableDatabase
            val formatCountBefore = writable.query(
                "SELECT COUNT(*) FROM format",
                emptyArray()
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
            writable.execSQL("DELETE FROM format")

            val formatCountAfter = writable.query(
                "SELECT COUNT(*) FROM format",
                emptyArray()
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
            val cacheDir = context.cacheDir
            val criticalCacheDirs = listOf(
                "exoplayer",           // Cache principal de ExoPlayer
                "player_cache",        // Cache alternativo
                "download_cache",      // Descargas parciales
                "media",               // Cache de medios
                "audio_cache",         // Cache de audio específico
                ".ExoPlayerCacheDir"   // Directorio oculto de ExoPlayer
            )

            var deletedCount = 0
            var totalSize = 0L

            criticalCacheDirs.forEach { dirName ->
                val dir = File(cacheDir, dirName)
                if (dir.exists() && dir.isDirectory) {
                    try {
                        // Calcular tamaño antes de borrar
                        val size = dir.walkTopDown()
                            .filter { it.isFile }
                            .map { it.length() }
                            .sum()

                        if (dir.deleteRecursively()) {
                            deletedCount++
                            totalSize += size
                            Log.d(TAG, "  ✅ $dirName (${size / 1024}KB)")
                        } else {
                            Log.w(TAG, "  ⚠️ No se pudo borrar $dirName")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "  ❌ Error en $dirName: ${e.message}")
                    }
                }
            }

            if (deletedCount > 0) {
                Log.i(
                    TAG,
                    "✅ Cache limpiado: $deletedCount directorios, ${totalSize / (1024 * 1024)}MB liberados"
                )
            } else {
                Log.d(TAG, "ℹ️ No había cache de reproducción para limpiar")
            }

            Log.d(TAG, "🗑️ Limpiando archivos temporales...")
            val tempFiles = cacheDir.listFiles { file ->
                (file.name.startsWith("import_") ||
                        file.name.startsWith("probe_")) &&
                        file.extension == "db"
            }

            tempFiles?.forEach { file ->
                try {
                    if (file.delete()) {
                        Log.d(TAG, "  ✅ ${file.name}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "  ⚠️ No se pudo borrar ${file.name}")
                }
            }

            try {
                Log.d(TAG, "🔧 Optimizando base de datos...")
                writable.execSQL("VACUUM")
                Log.d(TAG, "✅ Base de datos optimizada")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ VACUUM falló (no crítico): ${e.message}")
            }

            Log.i(TAG, "=== VERIFICACIÓN FINAL ===")
            val finalStats = mapOf(
                "format" to writable.query("SELECT COUNT(*) FROM format", emptyArray()).use {
                    if (it.moveToFirst()) it.getInt(0) else -1
                },
                "song" to writable.query("SELECT COUNT(*) FROM song", emptyArray()).use {
                    if (it.moveToFirst()) it.getInt(0) else -1
                }
            )

            Log.i(TAG, "📊 Registros finales:")
            finalStats.forEach { (table, count) ->
                Log.i(TAG, "  - $table: $count registros")
            }

            Log.i(TAG, "=== LIMPIEZA COMPLETADA EXITOSAMENTE ===")
            Log.w(TAG, "⚠️ IMPORTANTE: La primera reproducción regenerará las URLs de streaming")
            Log.w(TAG, "⚠️ RECOMENDADO: Reiniciar la app para aplicar todos los cambios")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error crítico en limpieza post-importación", e)
        }
    }


    suspend fun importFromOtherFork(
        uri: Uri,
        options: ImportOptions = ImportOptions()
    ): Result<ImportResult> {
        // Toda la migración (I/O + parsing + SQLite) se ejecuta fuera de Main.
        return withContext(Dispatchers.IO) {
            Log.i(TAG, "Iniciando proceso de migración con URI: $uri y opciones: $options")
            var tempDb: SQLiteDatabase? = null
            var tempFile: File? = null
            try {
                Log.d(TAG, "Copiando archivo fuente...")
                _progress.value = MigrateProgress.CopyingFile
                tempFile = copyUriToTempFile(uri) ?: throw Exception("Failed to copy file")

                Log.d(TAG, "Abriendo base de datos temporal...")
                _progress.value = MigrateProgress.OpeningDatabase
                tempDb = SQLiteDatabase.openDatabase(
                    tempFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY
                )

                Log.d(TAG, "Detectando tipo de fork...")
                val forkType = detectForkType(tempDb)
                Log.i(TAG, "Fork detectado: $forkType")
                _progress.value = MigrateProgress.DetectingFork(forkType)

                Log.d(TAG, "Comenzando migración según tipo de fork...")
                // Extract all data BEFORE closing the database
                val result = when (forkType) {
                    ForkType.INNERTUNE -> {
                        Log.i(TAG, "Migrando desde InnerTune...")
                        migrateFromInnerTune(tempDb, options)
                    }

                    ForkType.VIMUSIC -> {
                        Log.i(TAG, "Migrando desde ViMusic...")
                        migrateFromViMusic(tempDb, options)
                    }

                    ForkType.RIMUSIC -> {
                        Log.i(TAG, "Migrando desde RiMusic...")
                        migrateFromRiMusic(tempDb, options)
                    }

                    ForkType.HARMONY -> {
                        Log.i(TAG, "Migrando desde Harmony...")
                        migrateFromHarmony(tempDb, options)
                    }

                    ForkType.UNKNOWN -> {
                        Log.i(
                            TAG,
                            "Migrando desde fork desconocido en modo minimo (id/title/favorito)..."
                        )
                        migrateFromUnknownFork(tempDb, options)
                    }
                }

                Log.d(TAG, "Proceso de migración completado exitosamente")
                Log.i(TAG, "Iniciando limpieza post-importación...")
                _progress.value = MigrateProgress.CleaningSessionData
                cleanSessionDependentData()

                _progress.value = MigrateProgress.Done
                Result.success(result)

            } catch (e: Exception) {
                Log.e(TAG, "Error durante la importación", e)
                _progress.value = MigrateProgress.Error(e)
                Result.failure(e)
            } finally {
                Log.d(TAG, "Cerrando recursos...")
                // Close resources in reverse order
                try {
                    tempDb?.close()
                    Log.d(TAG, "Base de datos temporal cerrada")
                } catch (e: Exception) {
                    Log.w(TAG, "Error cerrando tempDb", e)
                }
                try {
                    tempFile?.delete()
                    Log.d(TAG, "Archivo temporal eliminado")
                } catch (e: Exception) {
                    Log.w(TAG, "Error eliminando archivo temporal", e)
                }
                Log.d(TAG, "Recursos cerrados")
            }
        }
    }

    // ---------- Insert Helpers ----------
    private fun insertSongsSync(songs: List<SongEntity>, overwriteExisting: Boolean = false): Int {
        val writable = database.openHelper.writableDatabase
        val sql = if (overwriteExisting) {
            """
        INSERT OR REPLACE INTO song
        (id, title, duration, thumbnailUrl, albumName, liked, likedDate, localPath)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        } else {
            // IGNORE evita excepción por PK duplicada; ya controlamos generación de IDs arriba
            """
        INSERT OR IGNORE INTO song
        (id, title, duration, thumbnailUrl, albumName, liked, likedDate, localPath)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        }

        var inserted = 0
        var ignored = 0
        var failed = 0

        writable.beginTransaction()
        val stmt = writable.compileStatement(sql)
        try {
            for (song in songs) {
                stmt.clearBindings()

                stmt.bindString(1, song.id)
                stmt.bindString(2, song.title ?: "Unknown")

                // duration es NOT NULL en la tabla song; -1 significa "desconocido".
                stmt.bindLong(3, song.duration.toLong().coerceAtLeast(-1L))

                if (!song.thumbnailUrl.isNullOrBlank()) {
                    stmt.bindString(4, song.thumbnailUrl)
                } else {
                    stmt.bindNull(4)
                }

                if (!song.albumName.isNullOrBlank()) {
                    stmt.bindString(5, song.albumName)
                } else {
                    stmt.bindNull(5)
                }

                stmt.bindLong(6, if (song.liked) 1L else 0L)

                if (song.likedDate != null) {
                    stmt.bindLong(
                        7,
                        song.likedDate.atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
                    )
                } else {
                    stmt.bindNull(7)
                }

                if (!song.localPath.isNullOrBlank()) {
                    stmt.bindString(8, song.localPath)
                } else {
                    stmt.bindNull(8)
                }

                try {
                    val rowId = stmt.executeInsert()
                    if (rowId == -1L) {
                        ignored++
                    } else {
                        inserted++
                    }
                } catch (e: Exception) {
                    failed++
                    Log.w(TAG, "Insert failed for song=${song.id}, continuing", e)
                    // no throw: continuamos con siguientes canciones
                }
            }

            writable.setTransactionSuccessful()
            Log.d(
                TAG,
                "Inserción chunk canciones -> solicitadas=${songs.size}, insertadas=$inserted, ignoradas=$ignored, fallidas=$failed"
            )
        } finally {
            try {
                stmt.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error cerrando statement", e)
            }
            writable.endTransaction()
        }
        return inserted
    }

    private fun insertPlaylistsSync(
        playlists: List<Pair<PlaylistEntity, List<PlaylistSongMap>>>,
        songIdMap: Map<String, String> = emptyMap()
    ) {
        val writable = database.openHelper.writableDatabase

        Log.i(TAG, "=== INSERTANDO ${playlists.size} PLAYLISTS ===")

        // Verificar estado de foreign keys
        try {
            val cursor = writable.query("PRAGMA foreign_keys", emptyArray())
            try {
                if (cursor.moveToFirst()) {
                    val fkEnabled = cursor.getInt(0) == 1
                    Log.i(
                        TAG,
                        "Foreign keys: ${if (fkEnabled) "HABILITADAS ✅" else "DESHABILITADAS ⚠️"}"
                    )
                }
            } finally {
                cursor.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo verificar estado de foreign keys", e)
        }

        val playlistSql = """
            INSERT OR IGNORE INTO playlist
            (id, name, isLocal, bookmarkedAt)
            VALUES (?, ?, 1, strftime('%s','now'))
        """.trimIndent()
        val deleteSql = "DELETE FROM playlist_song_map WHERE playlistId = ?"
        val mapSql = """
            INSERT OR REPLACE INTO playlist_song_map
            (playlistId, songId, position)
            VALUES (?, ?, ?)
        """.trimIndent()

        val playlistStmt = writable.compileStatement(playlistSql)
        val deleteStmt = writable.compileStatement(deleteSql)
        val mapStmt = writable.compileStatement(mapSql)

        var successfulPlaylists = 0
        var totalSongsInserted = 0
        var totalSongsSkipped = 0

        try {
            for ((index, playlistPair) in playlists.withIndex()) {
                val (playlist, songMaps) = playlistPair
                val playlistNumber = index + 1

                Log.i(TAG, "")
                Log.i(TAG, "[$playlistNumber/${playlists.size}] Procesando: '${playlist.name}'")
                Log.i(TAG, "  ID: ${playlist.id}")
                Log.i(TAG, "  Canciones a insertar: ${songMaps.size}")

                writable.beginTransaction()
                try {
                    // 1. Insertar playlist
                    playlistStmt.clearBindings()
                    playlistStmt.bindString(1, playlist.id)
                    playlistStmt.bindString(2, playlist.name)
                    val playlistRowId = playlistStmt.executeInsert()

                    if (playlistRowId == -1L) {
                        Log.w(TAG, "  ⚠️ Playlist ya existe, actualizando relaciones...")
                    } else {
                        Log.d(TAG, "  ✅ Playlist insertada (rowId: $playlistRowId)")
                    }

                    // 2. Limpiar relaciones anteriores
                    deleteStmt.clearBindings()
                    deleteStmt.bindString(1, playlist.id)
                    val deleted = deleteStmt.executeUpdateDelete()
                    if (deleted > 0) {
                        Log.d(TAG, "  🗑️ Eliminadas $deleted relaciones anteriores")
                    }

                    // 3. Validar y preparar canciones para insertar
                    if (songMaps.isEmpty()) {
                        Log.w(TAG, "  ⚠️ Playlist sin canciones")
                        writable.setTransactionSuccessful()
                        successfulPlaylists++
                        continue
                    }

                    Log.d(TAG, "  📝 Validando existencia de canciones...")

                    val candidateSongIds = LinkedHashSet<String>(songMaps.size)
                    songMaps.forEach { map ->
                        candidateSongIds.add(songIdMap[map.songId] ?: map.songId)
                    }

                    val existingSongIds = queryExistingSongIds(writable, candidateSongIds)
                    Log.d(TAG, "  ✅ Canciones existentes: ${existingSongIds.size}")

                    var missingCount = 0
                    val missingSample = ArrayList<String>(5)
                    var insertedCount = 0
                    var positionCounter = 0

                    for (map in songMaps) {
                        val mappedSongId = songIdMap[map.songId] ?: map.songId
                        if (!existingSongIds.contains(mappedSongId)) {
                            missingCount++
                            if (missingSample.size < 5) {
                                missingSample.add(mappedSongId)
                            }
                            continue
                        }

                        try {
                            mapStmt.clearBindings()
                            mapStmt.bindString(1, playlist.id)
                            mapStmt.bindString(2, mappedSongId)

                            val position = if (map.position >= 0) map.position else positionCounter
                            mapStmt.bindLong(3, position.toLong())

                            val rowId = mapStmt.executeInsert()
                            if (rowId > 0) {
                                insertedCount++
                                positionCounter++
                            } else {
                                Log.w(
                                    TAG,
                                    "     ⚠️ No se insertó relación para songId=$mappedSongId"
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(
                                TAG,
                                "     ❌ Error insertando relación songId=$mappedSongId: ${e.message}"
                            )
                        }
                    }

                    totalSongsInserted += insertedCount
                    totalSongsSkipped += missingCount

                    if (missingCount > 0) {
                        Log.w(TAG, "  ❌ Canciones NO encontradas: $missingCount")
                        if (missingSample.isNotEmpty()) {
                            Log.w(TAG, "     Primeras 5: $missingSample")
                        }
                    }

                    if (existingSongIds.isEmpty()) {
                        Log.w(TAG, "  ⚠️ NINGUNA canción existe en la BD - playlist quedará vacía")
                    }

                    Log.i(
                        TAG,
                        "  ✅ Insertadas $insertedCount relaciones de ${songMaps.size - missingCount} válidas"
                    )

                    // 4. Verificar inserción
                    val verifyCount = writable.query(
                        "SELECT COUNT(*) FROM playlist_song_map WHERE playlistId = ?",
                        arrayOf(playlist.id)
                    ).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getInt(0) else 0
                    }

                    Log.i(TAG, "  🔍 Verificación: $verifyCount relaciones en BD")

                    if (verifyCount != insertedCount) {
                        Log.e(
                            TAG,
                            "  ⚠️ DISCREPANCIA: Se insertaron $insertedCount pero BD muestra $verifyCount"
                        )
                    }

                    writable.setTransactionSuccessful()
                    successfulPlaylists++
                } catch (e: Exception) {
                    Log.e(TAG, "  ❌ Error procesando playlist '${playlist.name}'", e)
                    Log.e(TAG, "     Tipo: ${e.javaClass.simpleName}")
                    Log.e(TAG, "     Mensaje: ${e.message}")
                } finally {
                    try {
                        writable.endTransaction()
                    } catch (e: Exception) {
                        Log.e(TAG, "  ⚠️ Error cerrando transacción: ${e.message}")
                    }
                }
            }
        } finally {
            try {
                mapStmt.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error cerrando mapStmt", e)
            }
            try {
                deleteStmt.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error cerrando deleteStmt", e)
            }
            try {
                playlistStmt.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error cerrando playlistStmt", e)
            }
        }

        Log.i(TAG, "")
        Log.i(TAG, "=== RESUMEN INSERCIÓN PLAYLISTS ===")
        Log.i(TAG, "Playlists procesadas exitosamente: $successfulPlaylists/${playlists.size}")
        Log.i(TAG, "Total relaciones insertadas: $totalSongsInserted")
        Log.i(TAG, "Total canciones omitidas (no existen): $totalSongsSkipped")
        Log.i(TAG, "")
    }


    // ---------- Detect fork ----------
    private fun detectForkType(db: SQLiteDatabase): ForkType {
        Log.d(TAG, "Detectando tipo de fork...")
        return try {
            val tables = mutableListOf<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                Log.d(TAG, "Obteniendo lista de tablas...")
                while (c.moveToNext()) tables.add(c.getString(0))
            }

            Log.d(TAG, "Tablas encontradas: $tables")

            val detectedType = when {
                tables.contains("Song") && tables.contains("Album") && tables.contains("Artist") -> {
                    Log.d(TAG, "Detectado como InnerTune")
                    ForkType.INNERTUNE
                }

                tables.contains("songs") && tables.contains("playlists") -> {
                    Log.d(TAG, "Detectado como ViMusic")
                    ForkType.VIMUSIC
                }

                tables.contains("Song") && !tables.contains("Album") -> {
                    Log.d(TAG, "Detectado como RiMusic")
                    ForkType.RIMUSIC
                }

                tables.contains("queue") && hasColumn(db, "queue", "lastSongPos") -> {
                    Log.d(TAG, "Detectado como Harmony")
                    ForkType.HARMONY
                }

                else -> {
                    Log.d(TAG, "Tipo de fork desconocido")
                    ForkType.UNKNOWN
                }
            }
            Log.i(TAG, "Tipo de fork detectado: $detectedType")
            detectedType
        } catch (e: Exception) {
            Log.w(TAG, "Fallo en detectForkType", e)
            ForkType.UNKNOWN
        }
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean {
        Log.d(TAG, "Verificando si la columna '$column' existe en la tabla '$table'...")
        return try {
            db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                var found = false
                while (cursor.moveToNext()) {
                    val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    if (name == column) {
                        found = true
                        break
                    }
                }
                Log.d(
                    TAG,
                    "Columna '$column' ${if (found) "encontrada" else "no encontrada"} en tabla '$table'"
                )
                found
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error verificando columna", e)
            false
        }
    }

    private suspend fun migrateFromInnerTune(
        sourceDb: SQLiteDatabase,
        options: ImportOptions
    ): ImportResult {
        Log.d(TAG, "Iniciando migración desde InnerTune con opciones: $options")

        // Loguear estructura inicial para diagnóstico
        logTablePreview(sourceDb, "Song", 5)
        logTablePreview(sourceDb, "Playlist", 5)

        val songsToImport = if (options.importSongs && hasTable(sourceDb, "Song")) {
            Log.d(TAG, "Extrayendo canciones desde InnerTune...")
            extractSongsFromInnerTune(sourceDb)
        } else {
            Log.d(TAG, "Opción de importar canciones deshabilitada o tabla 'Song' no encontrada")
            emptyList()
        }
        val allTables = extractAvailableTables(sourceDb)
        val sourceSongArtists = if (options.importSongs) {
            val artistsFromRelationTables =
                extractSongArtistsFromRelationTables(sourceDb, allTables)
            val artistsFromSongTable = extractSongArtistsFromTable(sourceDb, "Song")
            mergeSongArtists(artistsFromSongTable, artistsFromRelationTables)
        } else {
            emptyMap()
        }

        val playlistsToImport = if (options.importPlaylists && hasTable(sourceDb, "Playlist")) {
            Log.d(TAG, "Extrayendo playlists desde InnerTune...")
            extractPlaylistsFromInnerTune(sourceDb, options.generateNewIds)
        } else {
            Log.d(
                TAG,
                "Opción de importar playlists deshabilitada o tabla 'Playlist' no encontrada"
            )
            emptyList()
        }

        Log.d(
            TAG,
            "Preparando inserción de ${songsToImport.size} canciones en chunks de $CHUNK_SIZE y ${playlistsToImport.size} playlists"
        )

        var songsImported = 0
        var playlistsImported = 0
        // Mapa para conservar correspondencia entre IDs originales y nuevos (si generateNewIds)
        val idMap = mutableMapOf<String, String>()
        var favoritesImported = 0

        val preExistingSongIds = if (options.generateNewIds && songsToImport.isNotEmpty()) {
            val sourceSongIds = LinkedHashSet<String>(songsToImport.size)
            songsToImport.forEach { song -> sourceSongIds.add(song.id) }
            queryExistingSongIds(database.openHelper.readableDatabase, sourceSongIds)
        } else {
            emptySet()
        }
        val usedSongIds = HashSet<String>(preExistingSongIds.size + CHUNK_SIZE).apply {
            addAll(preExistingSongIds)
        }

        forEachChunk(songsToImport) { chunkIndex, totalChunks, chunk ->
            Log.i(TAG, "Procesando chunk $chunkIndex/$totalChunks (${chunk.size} canciones)")

            val songsToInsert: List<SongEntity> = if (options.generateNewIds) {
                val transformedChunk = ArrayList<SongEntity>(chunk.size)
                chunk.forEach { song ->
                    val originalId = song.id
                    val collidesWithTarget = preExistingSongIds.contains(originalId)
                    val collidesWithinImport = usedSongIds.contains(originalId)
                    if (collidesWithTarget || collidesWithinImport) {
                        var newId: String
                        do {
                            newId = UUID.randomUUID().toString()
                        } while (usedSongIds.contains(newId))

                        // El mapeo para playlists/favoritos solo aplica cuando el conflicto es con datos ya existentes.
                        if (collidesWithTarget) {
                            idMap[originalId] = newId
                        }

                        transformedChunk.add(song.copy(id = newId))
                        usedSongIds.add(newId)
                    } else {
                        transformedChunk.add(song)
                        usedSongIds.add(originalId)
                    }
                }
                transformedChunk
            } else {
                chunk
            }

            songsToInsert.forEach { song ->
                if (song.liked) favoritesImported++
            }

            val insertedInChunk = insertSongsSync(songsToInsert)
            songsImported += insertedInChunk

            Log.i(
                TAG,
                "Chunk $chunkIndex completado. Insertadas en chunk: $insertedInChunk. Total insertadas: $songsImported"
            )
        }

        val artistsImported = importSongArtistsFromSource(sourceSongArtists, idMap)
        Log.i(TAG, "Mapeos song-artist importados (InnerTune): $artistsImported")

        try {
            logIdMappings(idMap)
        } catch (e: Exception) {
            Log.w(TAG, "Error logging id mappings", e)
        }

        if (playlistsToImport.isNotEmpty()) {
            Log.d(TAG, "Insertando ${playlistsToImport.size} playlists...")
            insertPlaylistsSync(playlistsToImport, idMap)
            playlistsImported = playlistsToImport.size
        }

        try {
            remapFavoritesInTarget(sourceDb, idMap)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron remapear favoritos", e)
        }

        Log.d(
            TAG,
            "Transacción completada: $songsImported canciones, $playlistsImported playlists, $favoritesImported favoritos"
        )

        val result = ImportResult(
            songsImported = songsImported,
            playlistsImported = playlistsImported,
            favoritesImported = favoritesImported,
            historyImported = 0,
            queueImported = false
        )

        Log.i(TAG, "Migración desde InnerTune completada: $result")
        return result
    }

    private fun extractSongsFromInnerTune(sourceDb: SQLiteDatabase): List<SongEntity> {
        Log.d(TAG, "Extrayendo canciones desde tabla Song de InnerTune...")
        val songs = mutableListOf<SongEntity>()
        try {
            // Verificar columnas disponibles primero
            val columns = getTableColumns(sourceDb, "Song")
            Log.d(TAG, "Columnas disponibles en tabla Song: $columns")

            // Construir query dinámicamente basado en columnas disponibles
            val selectColumns = mutableListOf<String>()
            val columnMap = mutableMapOf<String, Boolean>()

            // Mapear columnas disponibles
            columnMap["id"] = columns.contains("id")
            columnMap["title"] = columns.contains("title")
            columnMap["artistsText"] = columns.contains("artistsText")
            columnMap["album"] = columns.contains("album")
            columnMap["duration"] = columns.contains("duration")
            columnMap["thumbnailUrl"] = columns.contains("thumbnailUrl")
            columnMap["liked"] = columns.contains("liked")
            columnMap["likedDate"] = columns.contains("likedDate")
            columnMap["localPath"] = columns.contains("localPath")

            columnMap.forEach { (col, available) ->
                if (available) selectColumns.add(col)
            }

            if (selectColumns.isEmpty()) {
                Log.w(TAG, "No se encontraron columnas útiles en la tabla Song")
                return emptyList()
            }

            val query = "SELECT ${selectColumns.joinToString(", ")} FROM Song"
            Log.d(TAG, "Ejecutando query: $query")

            sourceDb.rawQuery(query, null).use { c ->
                Log.d(TAG, "Encontradas ${c.count} filas")
                val columnIndices = mutableMapOf<String, Int>()

                // Obtener índices de columnas
                selectColumns.forEach { col ->
                    columnIndices[col] = c.getColumnIndex(col)
                }

                var count = 0
                while (c.moveToNext()) {
                    try {
                        val id = if (columnIndices["id"] != -1)
                            c.getStringOrNull("id")?.takeIf { it.isNotBlank() }
                                ?: UUID.randomUUID().toString()
                        else UUID.randomUUID().toString()

                        val title = if (columnIndices["title"] != -1)
                            c.getStringOrNull("title")?.takeIf { it.isNotBlank() }
                                ?: "Unknown"
                        else "Unknown"

                        val duration = if (columnIndices["duration"] != -1) {
                            parseDurationAny(c.getStringOrNull("duration"))?.toLong() ?: -1L
                        } else {
                            -1L
                        }

                        val thumbnailUrl = if (columnIndices["thumbnailUrl"] != -1)
                            c.getStringOrNull("thumbnailUrl")?.takeIf { it.isNotBlank() }
                        else null

                        val albumName = if (columnIndices["album"] != -1)
                            c.getStringOrNull("album")?.takeIf { it.isNotBlank() }
                        else null

                        val liked = if (columnIndices["liked"] != -1)
                            c.getIntOrNull("liked") == 1
                        else false

                        val likedDate = if (columnIndices["likedDate"] != -1 && liked) {
                            val dateStr = c.getStringOrNull("likedDate")
                            dateStr?.let {
                                try {
                                    LocalDateTime.parse(it)
                                } catch (e: Exception) {
                                    null
                                }
                            }
                        } else null

                        val localPath = if (columnIndices["localPath"] != -1)
                            c.getStringOrNull("localPath")?.takeIf { it.isNotBlank() }
                        else null

                        val song = SongEntity(
                            id = id,
                            title = title,
                            duration = duration.toInt(),
                            thumbnailUrl = thumbnailUrl,
                            albumName = albumName,
                            liked = liked,
                            likedDate = likedDate,
                            localPath = localPath
                        )

                        songs.add(song)
                        count++

                        if (count % 500 == 0) {
                            Log.d(TAG, "Canciones extraídas: $count")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error extrayendo canción en fila $count", e)
                    }
                }
                Log.d(TAG, "Total canciones extraídas: $count")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error consultando tabla Song", e)
        }
        Log.d(TAG, "Finalizada extracción: ${songs.size} canciones")
        return songs
    }

    private fun extractPlaylistsFromInnerTune(
        sourceDb: SQLiteDatabase,
        generateNewIds: Boolean
    ): List<Pair<PlaylistEntity, List<PlaylistSongMap>>> {
        Log.d(TAG, "Extrayendo playlists desde InnerTune (generar nuevos IDs: $generateNewIds)...")
        val playlists = mutableListOf<Pair<PlaylistEntity, List<PlaylistSongMap>>>()
        try {
            sourceDb.rawQuery(
                "SELECT id, name, browseId FROM Playlist WHERE browseId IS NULL OR browseId = ''",
                null
            ).use { c ->
                Log.d(TAG, "Consulta de playlists ejecutada, procesando resultados...")
                var count = 0
                while (c.moveToNext()) {
                    try {
                        val playlistId = c.getStringOrNull("id") ?: UUID.randomUUID().toString()
                        val playlistName = c.getStringOrNull("name") ?: "Playlist"
                        val newId = if (generateNewIds) {
                            val generatedId = PlaylistEntity.generatePlaylistId()
                            generatedId
                        } else {
                            playlistId
                        }

                        val playlist = PlaylistEntity(id = newId, name = playlistName)

                        val songMaps = mutableListOf<PlaylistSongMap>()
                        try {
                            sourceDb.rawQuery(
                                "SELECT songId, position FROM PlaylistSongMap WHERE playlistId = ? ORDER BY position",
                                arrayOf(playlistId)
                            ).use { sc ->
                                while (sc.moveToNext()) {
                                    val songId = sc.getStringOrNull("songId") ?: continue
                                    val pos = sc.getIntOrNull("position") ?: 0
                                    songMaps.add(
                                        PlaylistSongMap(
                                            playlistId = newId,
                                            songId = songId,
                                            position = pos
                                        )
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error extrayendo canciones de playlist", e)
                        }

                        playlists.add(Pair(playlist, songMaps))
                        count++

                        if (count % 50 == 0) {
                            Log.d(TAG, "Playlists extraídas: $count")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Fallo al extraer playlist", e)
                    }
                }
                Log.d(TAG, "Total playlists extraídas: $count")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error consultando tabla Playlist", e)
        }
        Log.d(TAG, "Finalizada extracción de playlists: ${playlists.size} playlists")
        return playlists
    }

    private suspend fun migrateFromViMusic(
        sourceDb: SQLiteDatabase,
        options: ImportOptions
    ): ImportResult {
        Log.d(TAG, "Iniciando migración desde ViMusic con opciones: $options")

        logTablePreview(sourceDb, "songs", 5)
        logTablePreview(sourceDb, "playlists", 5)

        val songsToImport = if (options.importSongs && hasTable(sourceDb, "songs")) {
            Log.d(TAG, "Extrayendo canciones desde ViMusic...")
            extractSongsFromViMusic(sourceDb)
        } else {
            Log.d(TAG, "Opción de importar canciones deshabilitada o tabla 'songs' no encontrada")
            emptyList()
        }
        val allTables = extractAvailableTables(sourceDb)
        val sourceSongArtists = if (options.importSongs) {
            val artistsFromRelationTables =
                extractSongArtistsFromRelationTables(sourceDb, allTables)
            val artistsFromSongTable = extractSongArtistsFromTable(sourceDb, "songs")
            mergeSongArtists(artistsFromSongTable, artistsFromRelationTables)
        } else {
            emptyMap()
        }

        val playlistsToImport = if (options.importPlaylists && hasTable(sourceDb, "playlists")) {
            Log.d(TAG, "Extrayendo playlists desde ViMusic...")
            extractPlaylistsFromViMusic(sourceDb, options.generateNewIds)
        } else {
            Log.d(
                TAG,
                "Opción de importar playlists deshabilitada o tabla 'playlists' no encontrada"
            )
            emptyList()
        }

        Log.d(
            TAG,
            "Preparando inserción de ${songsToImport.size} canciones en chunks de $CHUNK_SIZE y ${playlistsToImport.size} playlists"
        )

        var songsImported = 0
        var playlistsImported = 0
        val idMap = mutableMapOf<String, String>()

        forEachChunk(songsToImport) { chunkIndex, totalChunks, chunk ->
            Log.i(TAG, "Procesando chunk $chunkIndex/$totalChunks (${chunk.size} canciones)")

            val songsToInsert = if (options.generateNewIds) {
                val transformedChunk = ArrayList<SongEntity>(chunk.size)
                chunk.forEach { song ->
                    val newId = UUID.randomUUID().toString()
                    idMap[song.id] = newId
                    transformedChunk.add(song.copy(id = newId))
                }
                transformedChunk
            } else {
                chunk
            }

            val insertedInChunk = insertSongsSync(songsToInsert)
            songsImported += insertedInChunk

            Log.i(
                TAG,
                "Chunk $chunkIndex completado. Insertadas en chunk: $insertedInChunk. Total insertadas: $songsImported"
            )
        }

        val artistsImported = importSongArtistsFromSource(sourceSongArtists, idMap)
        Log.i(TAG, "Mapeos song-artist importados (ViMusic): $artistsImported")

        try {
            logIdMappings(idMap)
        } catch (e: Exception) {
            Log.w(TAG, "Error logging id mappings ViMusic", e)
        }

        // Procesar playlists sincrónicamente
        if (playlistsToImport.isNotEmpty()) {
            Log.d(TAG, "Insertando ${playlistsToImport.size} playlists...")
            insertPlaylistsSync(playlistsToImport, idMap)
            playlistsImported = playlistsToImport.size
        }

        // Remap favorites
        try {
            remapFavoritesInTarget(sourceDb, idMap)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron remapear favoritos ViMusic", e)
        }

        Log.d(
            TAG,
            "Transacción ViMusic completada: $songsImported canciones, $playlistsImported playlists"
        )

        val result = ImportResult(
            songsImported = songsImported,
            playlistsImported = playlistsToImport.size,
            favoritesImported = 0,
            historyImported = 0,
            queueImported = false
        )

        Log.i(TAG, "Migración desde ViMusic completada: $result")
        return result
    }

    private fun extractSongsFromViMusic(sourceDb: SQLiteDatabase): List<SongEntity> {
        Log.d(TAG, "Extrayendo canciones desde tabla songs de ViMusic...")
        val songs = mutableListOf<SongEntity>()
        try {
            val columns = getTableColumns(sourceDb, "songs")
            val hasArtistsText = columns.any { it.equals("artistsText", ignoreCase = true) }
            val hasAlbumTitle = columns.any { it.equals("albumTitle", ignoreCase = true) }
            val hasDurationText = columns.any { it.equals("durationText", ignoreCase = true) }
            val hasDuration = columns.any { it.equals("duration", ignoreCase = true) }
            val hasThumbnail = columns.any { it.equals("thumbnailUrl", ignoreCase = true) }

            val selectCols = mutableListOf("id", "title").apply {
                if (hasArtistsText) add("artistsText")
                if (hasAlbumTitle) add("albumTitle")
                if (hasDurationText) add("durationText")
                if (hasDuration) add("duration")
                if (hasThumbnail) add("thumbnailUrl")
            }

            sourceDb.rawQuery(
                "SELECT ${selectCols.joinToString(", ")} FROM songs",
                null
            ).use { c ->
                Log.d(TAG, "Consulta ViMusic ejecutada, procesando resultados...")
                var count = 0
                while (c.moveToNext()) {
                    try {
                        // Saneamiento de ID
                        val id =
                            c.getStringOrNull("id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID()
                                .toString()

                        // Saneamiento de título
                        val title =
                            c.getStringOrNull("title")?.takeIf { it.isNotBlank() } ?: "Unknown"

                        // Saneamiento de álbum (opcional)
                        val album = c.getStringOrNull("albumTitle")?.takeIf { it.isNotBlank() }

                        // Saneamiento de duración (texto o numérico -> segundos)
                        val durationSeconds = parseDurationAny(
                            c.getStringOrNull("durationText") ?: c.getStringOrNull("duration")
                        )

                        // Saneamiento de miniatura (opcional)
                        val thumb = c.getStringOrNull("thumbnailUrl")?.takeIf { it.isNotBlank() }

                        // Crear la entidad con los datos saneados
                        val song = SongEntity(
                            id = id,
                            title = title,
                            duration = durationSeconds ?: -1,
                            thumbnailUrl = thumb,
                            albumName = album,
                            // liked, likedDate, localPath no parecen estar en la tabla ViMusic 'songs'
                            liked = false, // Asignar valor por defecto si no está en la fuente
                            likedDate = null,
                            localPath = null
                        )
                        songs.add(song)
                        count++
                        if (count % 500 == 0) {
                            Log.d(TAG, "Canciones ViMusic extraídas: $count")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Fallo al extraer o sanear canción ViMusic", e)
                        // Opcional: Continuar con la siguiente fila
                    }
                }
                Log.d(TAG, "Total canciones ViMusic extraídas: $count")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error consultando tabla songs desde ViMusic", e)
        }
        Log.d(TAG, "Finalizada extracción de canciones ViMusic: ${songs.size} canciones")
        return songs
    }

    private fun extractPlaylistsFromViMusic(
        sourceDb: SQLiteDatabase,
        generateNewIds: Boolean
    ): List<Pair<PlaylistEntity, List<PlaylistSongMap>>> {
        Log.d(TAG, "Extrayendo playlists desde ViMusic (generar nuevos IDs: $generateNewIds)...")
        val playlists = mutableListOf<Pair<PlaylistEntity, List<PlaylistSongMap>>>()
        try {
            sourceDb.rawQuery("SELECT id, name FROM playlists", null).use { c ->
                Log.d(TAG, "Consulta de playlists ViMusic ejecutada, procesando resultados...")
                var count = 0
                while (c.moveToNext()) {
                    try {
                        val playlistId = c.getStringOrNull("id") ?: UUID.randomUUID().toString()
                        val playlistName = c.getStringOrNull("name") ?: "Playlist"
                        val newId = if (generateNewIds) {
                            val generatedId = PlaylistEntity.generatePlaylistId()
                            generatedId
                        } else {
                            playlistId
                        }

                        val playlist = PlaylistEntity(id = newId, name = playlistName)

                        val songMaps = mutableListOf<PlaylistSongMap>()
                        try {
                            sourceDb.rawQuery(
                                "SELECT songId, position FROM SongPlaylistMap WHERE playlistId = ? ORDER BY position",
                                arrayOf(playlistId)
                            ).use { sc ->
                                while (sc.moveToNext()) {
                                    val songId = sc.getStringOrNull("songId") ?: continue
                                    val pos = sc.getIntOrNull("position") ?: 0
                                    songMaps.add(
                                        PlaylistSongMap(
                                            playlistId = newId,
                                            songId = songId,
                                            position = pos
                                        )
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error extrayendo canciones de playlist ViMusic", e)
                        }

                        playlists.add(Pair(playlist, songMaps))
                        count++

                        if (count % 50 == 0) {
                            Log.d(TAG, "Playlists ViMusic extraídas: $count")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Fallo al extraer playlist ViMusic", e)
                    }
                }
                Log.d(TAG, "Total playlists ViMusic extraídas: $count")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error consultando tabla playlists desde ViMusic", e)
        }
        Log.d(TAG, "Finalizada extracción de playlists ViMusic: ${playlists.size} playlists")
        return playlists
    }

    private suspend fun migrateFromRiMusic(
        sourceDb: SQLiteDatabase,
        options: ImportOptions
    ): ImportResult {
        Log.d(TAG, "Migrando desde RiMusic (usando lógica de ViMusic)...")
        return migrateFromViMusic(sourceDb, options)
    }

    private suspend fun migrateFromHarmony(
        sourceDb: SQLiteDatabase,
        options: ImportOptions
    ): ImportResult {
        Log.d(TAG, "Migrando desde Harmony (usando lógica de InnerTune)...")
        return migrateFromInnerTune(sourceDb, options)
    }

    private suspend fun migrateFromUnknownFork(
        sourceDb: SQLiteDatabase,
        options: ImportOptions
    ): ImportResult {
        Log.i(TAG, "Intentando migración genérica para fork desconocido con opciones: $options")

        val allTables = extractAvailableTables(sourceDb)
        logKeyTablesPreview(sourceDb, allTables)

        val (songsToImport, playlistsToImport, sourceSongArtists) = extractDataFromSource(
            sourceDb,
            allTables,
            options
        )

        return performImport(
            songsToImport = songsToImport,
            playlistsToImport = playlistsToImport,
            options = options,
            sourceSongArtists = sourceSongArtists
        )
    }

    /**
     * Extrae todas las tablas disponibles de la base de datos origen
     */
    private fun extractAvailableTables(sourceDb: SQLiteDatabase): List<String> {
        Log.d(TAG, "Obteniendo todas las tablas disponibles...")

        return sourceDb.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table'",
            null
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0))
                }
            }
        }.also { tables ->
            Log.i(TAG, "Tablas disponibles: $tables")
        }
    }

    /**
     * Registra un preview de las tablas clave (song y playlist)
     */
    private fun logKeyTablesPreview(sourceDb: SQLiteDatabase, allTables: List<String>) {
        val songTable = allTables.firstOrNull {
            it.equals("song", ignoreCase = true) || it.equals("songs", ignoreCase = true)
        } ?: "song"

        val playlistTable = allTables.firstOrNull {
            it.equals("playlist", ignoreCase = true) || it.equals("playlists", ignoreCase = true)
        } ?: "playlist"

        logTablePreview(sourceDb, songTable, PREVIEW_ROWS_COUNT)
        logTablePreview(sourceDb, playlistTable, PREVIEW_ROWS_COUNT)
    }

    /**
     * Extrae canciones y playlists de la base de datos origen según las opciones
     */
    private fun extractDataFromSource(
        sourceDb: SQLiteDatabase,
        allTables: List<String>,
        options: ImportOptions
    ): Triple<List<SongEntity>, List<Pair<PlaylistEntity, List<PlaylistSongMap>>>, Map<String, List<ArtistInfo>>> {
        val (songs, sourceSongArtists) = if (options.importSongs) {
            Log.d(TAG, "Extrayendo canciones genéricas...")
            val (genericSongs, artistsFromSongTable) = extractGenericSongs(sourceDb, allTables)
            val artistsFromRelationTables =
                extractSongArtistsFromRelationTables(sourceDb, allTables)
            val mergedArtists = mergeSongArtists(artistsFromSongTable, artistsFromRelationTables)
            Log.i(
                TAG,
                "Artistas detectados para UNKNOWN: song_table=${artistsFromSongTable.size}, " +
                        "relation_tables=${artistsFromRelationTables.size}, merged=${mergedArtists.size}"
            )
            Pair(genericSongs, mergedArtists)
        } else {
            Log.d(TAG, "Opción de importar canciones deshabilitada")
            Pair(emptyList(), emptyMap())
        }

        val playlists = if (options.importPlaylists) {
            Log.d(TAG, "Extrayendo playlists genéricas...")
            extractGenericPlaylists(sourceDb, allTables, options.generateNewIds)
        } else {
            Log.d(TAG, "Opción de importar playlists deshabilitada")
            emptyList()
        }

        return Triple(songs, playlists, sourceSongArtists)
    }

    /**
     * Realiza la importación de los datos extraídos
     */
    private suspend fun performImport(
        songsToImport: List<SongEntity>,
        playlistsToImport: List<Pair<PlaylistEntity, List<PlaylistSongMap>>>,
        options: ImportOptions,
        sourceSongArtists: Map<String, List<ArtistInfo>> = emptyMap()
    ): ImportResult {
        Log.d(
            TAG,
            "Preparando inserción de ${songsToImport.size} canciones en chunks de $CHUNK_SIZE " +
                    "y ${playlistsToImport.size} playlists"
        )

        val idMap = mutableMapOf<String, String>()
        val songsImported = importSongsInChunks(songsToImport, options.generateNewIds, idMap)
        val favoritesImported = songsToImport.count { it.liked }
        val artistsImported = importSongArtistsFromSource(sourceSongArtists, idMap)

        if (songsToImport.isNotEmpty()) {
            verifyImportedSongs(songsImported)
        } else {
            Log.i(TAG, "No hay canciones para verificar en esta importación")
        }

        val playlistsImported = importPlaylists(playlistsToImport, idMap)

        Log.d(
            TAG,
            "Transacción genérica completada: $songsImported canciones, " +
                    "$playlistsImported playlists, $favoritesImported favoritos, $artistsImported artistas"
        )

        return ImportResult(
            songsImported = songsImported,
            playlistsImported = playlistsImported,
            favoritesImported = favoritesImported,
            historyImported = 0,
            queueImported = false
        ).also { result ->
            Log.i(TAG, "Migración genérica completada: $result")
        }
    }

    /**
     * Importa canciones en chunks para mejor rendimiento
     */
    private suspend fun importSongsInChunks(
        songs: List<SongEntity>,
        generateNewIds: Boolean,
        idMap: MutableMap<String, String>
    ): Int {
        if (songs.isEmpty()) return 0

        var imported = 0
        forEachChunk(songs) { chunkIndex, totalChunks, chunk ->
            Log.i(TAG, "Procesando chunk $chunkIndex/$totalChunks (${chunk.size} canciones)")

            val songsToInsert = if (generateNewIds) {
                val transformedChunk = ArrayList<SongEntity>(chunk.size)
                chunk.forEach { song ->
                    val newId = UUID.randomUUID().toString()
                    idMap[song.id] = newId
                    transformedChunk.add(song.copy(id = newId))
                }
                transformedChunk
            } else {
                chunk
            }

            val insertedInChunk = insertSongsSync(songsToInsert)
            imported += insertedInChunk

            Log.i(
                TAG,
                "Chunk $chunkIndex completado. Insertadas en chunk: $insertedInChunk. Total insertadas: $imported"
            )
        }

        return imported
    }

    /**
     * Verifica que las canciones fueron insertadas correctamente
     */
    private fun verifyImportedSongs(expectedCount: Int) {
        try {
            Log.d(TAG, "🔄 Forzando flush de transacciones pendientes...")

            // Obtener la base de datos SQLite subyacente
            val db = database.openHelper.writableDatabase

            // PRAGMA wal_checkpoint devuelve filas, por lo que debe ejecutarse como query.
            db.query("PRAGMA wal_checkpoint(FULL)", emptyArray()).use { cursor ->
                if (cursor.moveToFirst()) {
                    val busy = cursor.getInt(0)
                    val log = cursor.getInt(1)
                    val checkpointed = cursor.getInt(2)
                    Log.d(TAG, "WAL checkpoint: busy=$busy, log=$log, checkpointed=$checkpointed")
                }
            }

            // Verificar inserción
            val actualCount = db.query("SELECT COUNT(*) FROM song", emptyArray()).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }

            Log.i(TAG, "✅ Verificación post-inserción: $actualCount canciones en BD")
            if (expectedCount > 0 && actualCount < expectedCount) {
                Log.w(
                    TAG,
                    "Inserción parcial detectada. Esperadas: $expectedCount, encontradas: $actualCount"
                )
            }

            if (actualCount == 0) {
                throw IllegalStateException(
                    "Failed to insert songs - database is empty after import"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error verificando inserción de canciones", e)
            throw e
        }
    }

    /**
     * Importa playlists y sus mapeos de canciones
     */
    private suspend fun importPlaylists(
        playlists: List<Pair<PlaylistEntity, List<PlaylistSongMap>>>,
        idMap: Map<String, String>
    ): Int {
        if (playlists.isEmpty()) return 0

        Log.d(TAG, "Insertando ${playlists.size} playlists...")

        insertPlaylistsSync(playlists, idMap)
        return playlists.size
    }


    /**
     * Excepción personalizada para errores de importación
     */
    class ImportException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    private fun extractGenericSongs(
        sourceDb: SQLiteDatabase,
        allTables: List<String>
    ): Pair<List<SongEntity>, Map<String, List<ArtistInfo>>> {
        Log.d(TAG, "Extrayendo canciones genericas desde tablas: $allTables")
        val songs = mutableListOf<SongEntity>()
        val songArtists = mutableMapOf<String, List<ArtistInfo>>()

        val songTable =
            allTables.firstOrNull { it.lowercase() == "song" || it.lowercase() == "songs" }
        if (songTable != null) {
            Log.d(TAG, "Tabla de canciones encontrada: $songTable")
            try {
                val columns = getTableColumns(sourceDb, songTable)
                Log.d(TAG, "Columnas de la tabla '$songTable': $columns")

                val idCol = columns.firstOrNull { it.lowercase() == "id" }
                val titleCol = columns.firstOrNull { it.lowercase() in listOf("title", "name") }
                val durationCol = columns.firstOrNull {
                    it.lowercase() in listOf(
                        "duration",
                        "durationsec",
                        "durationsecs",
                        "durationseconds",
                        "duration_ms",
                        "durationms",
                        "durationmillis",
                        "durationtext",
                        "length",
                        "lengthsec",
                        "lengthsecs",
                        "lengthtext"
                    )
                }
                val artistCol = findPreferredColumn(
                    columns = columns,
                    preferredNames = listOf(
                        "artiststext",
                        "artists_text",
                        "artist_text",
                        "artistnames",
                        "artist_names",
                        "artists",
                        "artist",
                        "artistname",
                        "artist_name",
                        "author",
                        "uploader",
                        "channel"
                    )
                )
                val artistIdCol = findPreferredColumn(
                    columns = columns,
                    preferredNames = listOf(
                        "artistid",
                        "artist_id",
                        "artistId",
                        "artistsid",
                        "artists_id",
                        "authorid",
                        "author_id",
                        "channelid",
                        "channel_id",
                        "uploaderid",
                        "uploader_id"
                    )
                )?.takeIf { !it.equals(artistCol, ignoreCase = true) }
                val likedCol = columns.firstOrNull {
                    it.lowercase() in listOf(
                        "liked",
                        "isliked",
                        "is_liked",
                        "isfavorite",
                        "is_favorite",
                        "favorite"
                    )
                }
                val thumbnailCol = columns.firstOrNull {
                    it.lowercase() in listOf(
                        "thumbnailurl",
                        "thumbnail",
                        "image",
                        "imageurl",
                        "coverurl",
                        "artworkurl",
                        "albumart"
                    )
                }

                Log.d(
                    TAG,
                    "Columnas identificadas (modo minimo) - id: $idCol, title: $titleCol, duration: $durationCol, artist: $artistCol, artistId: $artistIdCol, liked: $likedCol, thumbnail: $thumbnailCol"
                )

                if (idCol != null && titleCol != null) {
                    val selectCols = listOfNotNull(
                        idCol,
                        titleCol,
                        durationCol,
                        artistCol,
                        artistIdCol,
                        likedCol,
                        thumbnailCol
                    ).joinToString(", ")
                    val query = "SELECT $selectCols FROM $songTable"
                    Log.d(TAG, "Ejecutando consulta generica: $query")

                    sourceDb.rawQuery(query, null).use { c ->
                        Log.d(TAG, "Encontradas ${c.count} filas en $songTable")
                        var extractedCount = 0

                        while (c.moveToNext()) {
                            try {
                                val id = c.getStringOrNull(idCol)?.takeIf { it.isNotBlank() }
                                    ?: UUID.randomUUID().toString()
                                val title = c.getStringOrNull(titleCol)?.takeIf { it.isNotBlank() }
                                    ?: "Unknown"

                                val liked = if (likedCol != null) {
                                    when (c.getStringOrNull(likedCol)?.trim()?.lowercase()) {
                                        "1", "true", "yes" -> true
                                        else -> false
                                    }
                                } else {
                                    false
                                }

                                val thumbnail = if (thumbnailCol != null) {
                                    c.getStringOrNull(thumbnailCol)
                                } else {
                                    null
                                }

                                val duration = if (durationCol != null) {
                                    parseDurationAny(c.getStringOrNull(durationCol)) ?: -1
                                } else {
                                    -1
                                }
                                val artistText = if (artistCol != null) {
                                    c.getStringOrNull(artistCol)?.trim()?.takeIf { it.isNotBlank() }
                                } else {
                                    null
                                }
                                val artistIdText = if (artistIdCol != null) {
                                    c.getStringOrNull(artistIdCol)?.trim()
                                        ?.takeIf { it.isNotBlank() }
                                } else {
                                    null
                                }

                                songs.add(
                                    SongEntity(
                                        id = id,
                                        title = title,
                                        duration = duration,
                                        thumbnailUrl = thumbnail?.takeIf { it.isNotBlank() },
                                        albumName = null,
                                        liked = liked,
                                        likedDate = if (liked) LocalDateTime.now() else null,
                                        localPath = null
                                    )
                                )
                                if (!artistText.isNullOrBlank()) {
                                    val artists = parseArtistInfos(artistText, artistIdText)
                                    if (artists.isNotEmpty()) {
                                        songArtists[id] = artists
                                    }
                                }
                                extractedCount++

                                if (extractedCount % 500 == 0) {
                                    Log.d(TAG, "Canciones genericas extraidas: $extractedCount")
                                }
                            } catch (e: Exception) {
                                Log.w(
                                    TAG,
                                    "Fallo al extraer cancion generica en fila $extractedCount",
                                    e
                                )
                            }
                        }
                        Log.d(TAG, "Extraccion generica completada: $extractedCount canciones")
                    }
                } else {
                    Log.w(TAG, "No se encontraron columnas ID o Title en la tabla de canciones")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error extrayendo desde tabla de canciones", e)
            }
        } else {
            Log.w(TAG, "No se encontro tabla de canciones para migracion generica")
        }

        Log.d(TAG, "Finalizada extraccion de canciones genericas: ${songs.size} canciones")
        return Pair(songs, songArtists)
    }

    private fun mergeSongArtists(
        artistsFromSongTable: Map<String, List<ArtistInfo>>,
        artistsFromRelationTables: Map<String, List<ArtistInfo>>
    ): Map<String, List<ArtistInfo>> {
        if (artistsFromSongTable.isEmpty() && artistsFromRelationTables.isEmpty()) {
            return emptyMap()
        }

        val merged = linkedMapOf<String, List<ArtistInfo>>()
        val allSongIds = linkedSetOf<String>().apply {
            addAll(artistsFromSongTable.keys)
            addAll(artistsFromRelationTables.keys)
        }

        allSongIds.forEach { sourceSongId ->
            val artists = mutableListOf<ArtistInfo>()
            artistsFromRelationTables[sourceSongId]?.let { artists.addAll(it) }
            artistsFromSongTable[sourceSongId]?.let { artists.addAll(it) }

            val normalized = dedupeArtistInfos(artists)
            if (normalized.isNotEmpty()) {
                merged[sourceSongId] = normalized
            }
        }

        return merged
    }

    private fun extractGenericPlaylists(
        sourceDb: SQLiteDatabase,
        allTables: List<String>,
        generateNewIds: Boolean
    ): List<Pair<PlaylistEntity, List<PlaylistSongMap>>> {
        Log.d(
            TAG,
            "Extrayendo playlists genéricas (generar nuevos IDs: $generateNewIds), tablas disponibles: $allTables"
        )
        val playlists = mutableListOf<Pair<PlaylistEntity, List<PlaylistSongMap>>>()

        val playlistTable =
            allTables.firstOrNull { it.lowercase() == "playlist" || it.lowercase() == "playlists" }
        if (playlistTable != null) {
            Log.d(TAG, "Tabla de playlists encontrada: $playlistTable")
            try {
                val columns = getTableColumns(sourceDb, playlistTable)
                Log.d(TAG, "Columnas de la tabla '$playlistTable': $columns")

                val idCol = columns.firstOrNull { it.lowercase() == "id" }
                val nameCol = columns.firstOrNull { it.lowercase() in listOf("name", "title") }
                val browseIdCol = columns.firstOrNull { it.lowercase() == "browseid" }

                Log.d(
                    TAG,
                    "Columnas identificadas - id: $idCol, name: $nameCol, browseId: $browseIdCol"
                )

                if (idCol != null && nameCol != null) {
                    val whereClause = if (browseIdCol != null) {
                        " WHERE $browseIdCol IS NULL OR $browseIdCol = ''"
                    } else {
                        ""
                    }

                    val query = "SELECT $idCol, $nameCol FROM $playlistTable$whereClause"
                    Log.d(TAG, "Ejecutando consulta de playlists genéricas: $query")

                    sourceDb.rawQuery(query, null).use { c ->
                        Log.d(TAG, "Encontradas ${c.count} playlists genéricas")
                        var extractedCount = 0

                        while (c.moveToNext()) {
                            try {
                                val playlistId =
                                    c.getStringOrNull(idCol) ?: UUID.randomUUID().toString()
                                val playlistName = c.getStringOrNull(nameCol) ?: "Playlist"
                                val newId = if (generateNewIds) {
                                    val generatedId = PlaylistEntity.generatePlaylistId()
                                    generatedId
                                } else {
                                    playlistId
                                }

                                val playlist = PlaylistEntity(id = newId, name = playlistName)

                                // Extract playlist songs
                                val songMaps = mutableListOf<PlaylistSongMap>()
                                val mapTable = allTables.firstOrNull {
                                    it.lowercase() in listOf(
                                        "playlistsongmap",
                                        "playlist_song_map",
                                        "songplaylistmap",
                                        "song_playlist_map"
                                    )
                                }

                                if (mapTable != null) {
                                    try {
                                        sourceDb.rawQuery(
                                            "SELECT songId, position FROM $mapTable WHERE playlistId = ? ORDER BY position",
                                            arrayOf(playlistId)
                                        ).use { sc ->
                                            while (sc.moveToNext()) {
                                                val songId =
                                                    sc.getStringOrNull("songId") ?: continue
                                                val pos = sc.getIntOrNull("position") ?: 0
                                                songMaps.add(
                                                    PlaylistSongMap(
                                                        playlistId = newId,
                                                        songId = songId,
                                                        position = pos
                                                    )
                                                )
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.w(
                                            TAG,
                                            "Error extrayendo canciones para playlist genérica '$playlistName'",
                                            e
                                        )
                                    }
                                }

                                playlists.add(Pair(playlist, songMaps))
                                extractedCount++

                                if (extractedCount % 50 == 0) {
                                    Log.d(TAG, "Playlists genéricas extraídas: $extractedCount")
                                }
                            } catch (e: Exception) {
                                Log.w(
                                    TAG,
                                    "Fallo al extraer playlist genérica en fila $extractedCount",
                                    e
                                )
                            }
                        }
                        Log.d(
                            TAG,
                            "Extracción genérica de playlists completada: $extractedCount playlists"
                        )
                    }
                } else {
                    Log.w(TAG, "No se encontraron columnas ID o Name en la tabla de playlists")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error extrayendo desde tabla de playlists", e)
            }
        } else {
            Log.w(TAG, "No se encontró tabla de playlists para migración genérica")
        }

        Log.d(TAG, "Finalizada extracción de playlists genéricas: ${playlists.size} playlists")
        return playlists
    }

    // ---------- Helpers ----------
    private fun getTableColumns(db: SQLiteDatabase, tableName: String): List<String> {
        Log.v(TAG, "Obteniendo columnas para tabla: $tableName")
        return try {
            val columns = mutableListOf<String>()
            db.rawQuery("PRAGMA table_info($tableName)", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    columns.add(name)
                }
            }
            columns
        } catch (e: Exception) {
            Log.w(TAG, "Error obteniendo columnas para tabla $tableName", e)
            emptyList()
        }
    }

    private inline fun <T> forEachChunk(
        items: List<T>,
        chunkSize: Int = CHUNK_SIZE,
        block: (chunkIndex: Int, totalChunks: Int, chunk: List<T>) -> Unit
    ) {
        if (items.isEmpty()) return

        val totalChunks = ((items.size - 1) / chunkSize) + 1
        var startIndex = 0
        var chunkIndex = 0

        while (startIndex < items.size) {
            val endIndex = minOf(startIndex + chunkSize, items.size)
            chunkIndex++
            block(chunkIndex, totalChunks, items.subList(startIndex, endIndex))
            startIndex = endIndex
        }
    }

    private fun queryExistingSongIds(
        db: SupportSQLiteDatabase,
        songIds: Collection<String>
    ): Set<String> {
        if (songIds.isEmpty()) return emptySet()

        val inputIds = if (songIds is List<String>) songIds else songIds.toList()
        val existingSongIds = HashSet<String>(inputIds.size)
        var startIndex = 0

        while (startIndex < inputIds.size) {
            val endIndex = minOf(startIndex + SQLITE_MAX_BIND_ARGS, inputIds.size)
            val batchSize = endIndex - startIndex

            val placeholders = buildString(batchSize * 2) {
                repeat(batchSize) { idx ->
                    if (idx > 0) append(',')
                    append('?')
                }
            }
            val args = Array<Any>(batchSize) { idx -> inputIds[startIndex + idx] }

            db.query("SELECT id FROM song WHERE id IN ($placeholders)", args).use { cursor ->
                while (cursor.moveToNext()) {
                    val songId = cursor.getString(0)
                    if (!songId.isNullOrBlank()) {
                        existingSongIds.add(songId)
                    }
                }
            }

            startIndex = endIndex
        }

        return existingSongIds
    }

    private fun logTablePreview(db: SQLiteDatabase, tableName: String, limit: Int = 5) {
        try {
            if (!hasTable(db, tableName)) {
                Log.d(TAG, "Tabla '$tableName' no encontrada en fuente")
                return
            }
            val cols = getTableColumns(db, tableName)
            Log.i(TAG, "Preview tabla '$tableName' - columnas: $cols")

            val query = "SELECT * FROM $tableName LIMIT $limit"
            db.rawQuery(query, null).use { c ->
                val rowCount = c.count
                var logged = 0
                while (c.moveToNext()) {
                    val row = cols.mapIndexed { idx, col ->
                        val value = try {
                            c.getString(c.getColumnIndexOrThrow(col))
                        } catch (e: Exception) {
                            null
                        }
                        "$col=$value"
                    }.joinToString(", ")
                    Log.i(TAG, "$tableName row ${logged + 1}: $row")
                    logged++
                }
                Log.i(TAG, "Mostradas $logged/$rowCount filas de '$tableName'")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error mostrando preview para tabla $tableName", e)
        }
    }

    private fun remapFavoritesInTarget(sourceDb: SQLiteDatabase, idMap: Map<String, String>) {
        if (idMap.isEmpty()) {
            Log.d(TAG, "No hay mapeos de ID para remapear favoritos")
            return
        }

        val candidates = listOf(
            "favorites",
            "favourites",
            "liked_songs",
            "liked",
            "favorite_songs",
            "favorite",
            "bookmarks",
            "bookmark"
        )
        val writable = database.openHelper.writableDatabase

        for (table in candidates) {
            if (!hasTable(sourceDb, table)) continue

            val cols = getTableColumns(sourceDb, table)
            val idCol = cols.firstOrNull {
                it.equals("songId", true) || it.equals(
                    "song_id",
                    true
                ) || it.equals("id", true) || it.equals("trackId", true) || it.equals(
                    "track_id",
                    true
                )
            }
            if (idCol == null) {
                Log.d(TAG, "Tabla $table encontrada pero no se identificó columna de songId: $cols")
                continue
            }

            Log.i(TAG, "Remapeando favoritos desde tabla '$table' (columna $idCol)")
            var total = 0
            var updated = 0
            sourceDb.rawQuery("SELECT $idCol FROM $table", null).use { c ->
                while (c.moveToNext()) {
                    val oldId = try {
                        c.getString(c.getColumnIndexOrThrow(idCol))
                    } catch (e: Exception) {
                        null
                    }
                    if (oldId.isNullOrBlank()) continue
                    total++
                    val newId = idMap[oldId]
                    if (newId != null) {
                        try {
                            val stmt =
                                writable.compileStatement("UPDATE song SET liked = 1, likedDate = ? WHERE id = ?")
                            stmt.bindString(1, LocalDateTime.now().toString())
                            stmt.bindString(2, newId)
                            val rows = stmt.executeUpdateDelete()
                            stmt.close()
                            if (rows > 0) updated += rows
                        } catch (e: Exception) {
                            Log.w(TAG, "Error actualizando favorito para nuevoId=$newId", e)
                        }
                    } else {
                        Log.v(TAG, "No existe mapping para favorite oldId=$oldId")
                    }
                }
            }
            Log.i(TAG, "Favs procesados en '$table': $total, actualizados en target: $updated")
        }
    }

    private fun logIdMappings(idMap: Map<String, String>, sampleLimit: Int = 20) {
        try {
            if (idMap.isEmpty()) {
                Log.d(TAG, "No ID mappings to log")
                return
            }

            Log.i(TAG, "ID mappings count: ${idMap.size}")
            val ytRegex = Regex("^[A-Za-z0-9_-]{11}$")
            var printed = 0
            for ((oldId, newId) in idMap) {
                if (printed >= sampleLimit) break
                val note = if (ytRegex.matches(oldId)) "(looks-like-YT)" else ""
                Log.i(TAG, "ID map sample: old=$oldId $note -> new=$newId")
                printed++
            }
            val ytCount = idMap.keys.count { ytRegex.matches(it) }
            Log.i(TAG, "Mappings where original looks like YouTube ID: $ytCount/${idMap.size}")
        } catch (e: Exception) {
            Log.w(TAG, "Error while logging ID mappings", e)
        }
    }

    private fun hasTable(db: SQLiteDatabase, tableName: String): Boolean {
        return try {
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(tableName)
            ).use { c ->
                c.count > 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error verificando tabla $tableName", e)
            false
        }
    }

    private fun extractSongArtistsFromRelationTables(
        sourceDb: SQLiteDatabase,
        allTables: List<String>
    ): Map<String, List<ArtistInfo>> {
        val mapTable = allTables.firstOrNull {
            it.equals("song_artist_map", ignoreCase = true) ||
                    it.equals("sorted_song_artist_map", ignoreCase = true) ||
                    it.equals("songartistmap", ignoreCase = true) ||
                    it.equals("song_artist", ignoreCase = true)
        } ?: return emptyMap()

        val artistTable = allTables.firstOrNull {
            it.equals("artist", ignoreCase = true) || it.equals("artists", ignoreCase = true)
        } ?: return emptyMap()

        return try {
            val mapCols = getTableColumns(sourceDb, mapTable)
            val artistCols = getTableColumns(sourceDb, artistTable)

            val songIdCol = findPreferredColumn(
                columns = mapCols,
                preferredNames = listOf(
                    "songId",
                    "song_id",
                    "songid",
                    "song",
                    "trackId",
                    "track_id"
                )
            ) ?: return emptyMap()
            val artistIdCol = findPreferredColumn(
                columns = mapCols,
                preferredNames = listOf("artistId", "artist_id", "artistid", "artist")
            ) ?: return emptyMap()
            val positionCol = findPreferredColumn(
                columns = mapCols,
                preferredNames = listOf("position", "order", "idx", "index")
            )

            val artistPkCol = findPreferredColumn(
                columns = artistCols,
                preferredNames = listOf("id", "artistId", "artist_id")
            ) ?: return emptyMap()
            val artistNameCol = findPreferredColumn(
                columns = artistCols,
                preferredNames = listOf("name", "artist", "artistName", "artist_name", "title")
            ) ?: return emptyMap()

            val orderBy = if (positionCol != null) {
                "ORDER BY m.$songIdCol, m.$positionCol"
            } else {
                "ORDER BY m.$songIdCol"
            }

            val query = """
                SELECT m.$songIdCol AS sourceSongId, m.$artistIdCol AS sourceArtistId, a.$artistNameCol AS artistName
                FROM $mapTable m
                JOIN $artistTable a ON m.$artistIdCol = a.$artistPkCol
                $orderBy
            """.trimIndent()

            val songArtists = linkedMapOf<String, MutableList<ArtistInfo>>()
            sourceDb.rawQuery(query, null).use { cursor ->
                while (cursor.moveToNext()) {
                    val sourceSongId =
                        cursor.getStringOrNull("sourceSongId")?.takeIf { it.isNotBlank() }
                            ?: continue
                    val artistId =
                        cursor.getStringOrNull("sourceArtistId")?.trim()?.takeIf { it.isNotBlank() }
                            ?: continue
                    val artistName =
                        cursor.getStringOrNull("artistName")?.trim()?.takeIf { it.isNotBlank() }
                            ?: continue
                    val bucket = songArtists.getOrPut(sourceSongId) { mutableListOf() }
                    bucket.add(
                        ArtistInfo(
                            id = artistId,
                            name = artistName,
                            isYouTubeId = isLikelyYouTubeArtistId(artistId)
                        )
                    )
                }
            }

            songArtists.mapValues { (_, artists) -> dedupeArtistInfos(artists) }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron extraer artistas desde tablas relacionales", e)
            emptyMap()
        }
    }

    private fun extractSongArtistsFromTable(
        sourceDb: SQLiteDatabase,
        tableName: String
    ): Map<String, List<ArtistInfo>> {
        return try {
            if (!hasTable(sourceDb, tableName)) return emptyMap()

            val columns = getTableColumns(sourceDb, tableName)
            val idCol =
                columns.firstOrNull { it.equals("id", ignoreCase = true) } ?: return emptyMap()
            val artistCol = findPreferredColumn(
                columns = columns,
                preferredNames = listOf(
                    "artiststext",
                    "artists_text",
                    "artist_text",
                    "artistnames",
                    "artist_names",
                    "artists",
                    "artist",
                    "artistname",
                    "artist_name",
                    "author",
                    "uploader",
                    "channel"
                )
            ) ?: return emptyMap()
            val artistIdCol = findPreferredColumn(
                columns = columns,
                preferredNames = listOf(
                    "artistid",
                    "artist_id",
                    "artistId",
                    "artistsid",
                    "artists_id",
                    "authorid",
                    "author_id",
                    "channelid",
                    "channel_id",
                    "uploaderid",
                    "uploader_id"
                )
            )?.takeIf { !it.equals(artistCol, ignoreCase = true) }

            val selectCols = listOfNotNull(idCol, artistCol, artistIdCol).joinToString(", ")
            val songArtists = linkedMapOf<String, List<ArtistInfo>>()
            sourceDb.rawQuery("SELECT $selectCols FROM $tableName", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val sourceSongId =
                        cursor.getStringOrNull(idCol)?.takeIf { it.isNotBlank() } ?: continue
                    val artistText =
                        cursor.getStringOrNull(artistCol)?.takeIf { it.isNotBlank() } ?: continue
                    val artistIdsText = artistIdCol?.let { cursor.getStringOrNull(it) }
                    val artists = parseArtistInfos(artistText, artistIdsText)
                    if (artists.isNotEmpty()) {
                        songArtists[sourceSongId] = artists
                    }
                }
            }

            Log.d(
                TAG,
                "Extracción de artistas desde '$tableName': ${songArtists.size} canciones con artista"
            )
            songArtists
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron extraer artistas desde '$tableName'", e)
            emptyMap()
        }
    }

    private fun importSongArtistsFromSource(
        sourceSongArtists: Map<String, List<ArtistInfo>>,
        idMap: Map<String, String>
    ): Int {
        if (sourceSongArtists.isEmpty()) {
            return 0
        }

        val artistIdByName = mutableMapOf<String, String>()
        val existingArtistIds = mutableSetOf<String>()
        var insertedMappings = 0
        val targetSongIds = LinkedHashSet<String>(sourceSongArtists.size).apply {
            sourceSongArtists.keys.forEach { sourceSongId ->
                add(idMap[sourceSongId] ?: sourceSongId)
            }
        }
        val existingTargetSongIds =
            queryExistingSongIds(database.openHelper.readableDatabase, targetSongIds)

        try {
            // Precargar artistas existentes para no duplicar nombres.
            database.openHelper.readableDatabase
                .query("SELECT id, name FROM artist", emptyArray())
                .use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getStringOrNull("id") ?: continue
                        existingArtistIds.add(id)
                        val name = cursor.getStringOrNull("name")?.trim()?.lowercase() ?: continue
                        if (name.isNotBlank()) {
                            artistIdByName.putIfAbsent(name, id)
                        }
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo precargar tabla artist", e)
        }

        sourceSongArtists.forEach { (sourceSongId, artists) ->
            val targetSongId = idMap[sourceSongId] ?: sourceSongId
            if (!existingTargetSongIds.contains(targetSongId)) return@forEach

            dedupeArtistInfos(artists).forEachIndexed { index, artistInfo ->
                val normalizedName = artistInfo.name.trim()
                if (normalizedName.isBlank()) return@forEachIndexed

                val nameKey = normalizedName.lowercase()
                val sourceArtistId = artistInfo.id.trim()

                val artistId = if (sourceArtistId.isNotBlank()) {
                    if (!existingArtistIds.contains(sourceArtistId)) {
                        database.insert(
                            ArtistEntity(
                                id = sourceArtistId,
                                name = normalizedName,
                                isLocal = !artistInfo.isYouTubeId
                            )
                        )
                        existingArtistIds.add(sourceArtistId)
                    }
                    artistIdByName.putIfAbsent(nameKey, sourceArtistId)
                    sourceArtistId
                } else {
                    artistIdByName[nameKey] ?: run {
                        val newId = ArtistEntity.generateArtistId()
                        database.insert(
                            ArtistEntity(
                                id = newId,
                                name = normalizedName,
                                isLocal = true
                            )
                        )
                        existingArtistIds.add(newId)
                        artistIdByName[nameKey] = newId
                        newId
                    }
                }

                if (!artistInfo.isYouTubeId) {
                    // IDs locales (LA...) no deben tratarse como artistas remotos.
                    enforceLocalFlagForImportedArtist(artistId)
                }

                database.insert(
                    SongArtistMap(
                        songId = targetSongId,
                        artistId = artistId,
                        position = index
                    )
                )
                insertedMappings++
            }
        }

        return insertedMappings
    }

    private fun dedupeArtistInfos(artists: List<ArtistInfo>): List<ArtistInfo> {
        if (artists.isEmpty()) return emptyList()

        val deduped = mutableListOf<ArtistInfo>()
        val seen = mutableSetOf<String>()

        artists.forEach { artist ->
            val normalizedName = artist.name.trim()
            if (normalizedName.isBlank()) return@forEach

            val normalizedId = artist.id.trim()
            val normalizedArtist = artist.copy(id = normalizedId, name = normalizedName)
            val key = if (normalizedArtist.isYouTubeId && normalizedId.isNotBlank()) {
                "yt:${normalizedId.lowercase()}"
            } else {
                "name:${normalizedName.lowercase()}"
            }

            if (seen.add(key)) {
                deduped.add(normalizedArtist)
            }
        }

        return deduped
    }

    private fun parseArtistInfos(
        artistsText: String,
        artistIdsText: String? = null
    ): List<ArtistInfo> {
        if (artistsText.isBlank()) return emptyList()

        val artistsFromJson = parseArtistInfosFromJsonObjects(artistsText)
        if (artistsFromJson.isNotEmpty()) {
            return dedupeArtistInfos(artistsFromJson)
        }

        val names = parseArtistsText(artistsText)
        if (names.isEmpty()) return emptyList()

        val ids = parseArtistIdsText(artistIdsText)
        val result = when {
            ids.size == names.size -> names.mapIndexed { index, name ->
                val artistId = ids[index]
                ArtistInfo(
                    id = artistId,
                    name = name,
                    isYouTubeId = isLikelyYouTubeArtistId(artistId)
                )
            }

            ids.size == 1 -> {
                val firstId = ids.first()
                names.mapIndexed { index, name ->
                    if (index == 0) {
                        ArtistInfo(
                            id = firstId,
                            name = name,
                            isYouTubeId = isLikelyYouTubeArtistId(firstId)
                        )
                    } else {
                        ArtistInfo(
                            id = "",
                            name = name,
                            isYouTubeId = false
                        )
                    }
                }
            }

            else -> names.map { name ->
                ArtistInfo(
                    id = "",
                    name = name,
                    isYouTubeId = false
                )
            }
        }

        return dedupeArtistInfos(result)
    }

    private fun parseArtistInfosFromJsonObjects(rawArtistsText: String): List<ArtistInfo> {
        val normalized = rawArtistsText.trim().replace("\\\"", "\"")
        val objectRegex = Regex("\\{[^{}]+\\}")
        val artists = mutableListOf<ArtistInfo>()

        objectRegex.findAll(normalized).forEach { match ->
            val block = match.value
            val artistName = findJsonValue(
                block,
                listOf("name", "artist", "artistName", "artist_name", "author", "channel", "title")
            )?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach

            val artistId = findJsonValue(
                block,
                listOf(
                    "id",
                    "artistId",
                    "artist_id",
                    "channelId",
                    "channel_id",
                    "browseId",
                    "browse_id"
                )
            )?.trim()?.takeIf { it.isNotBlank() }

            artists.add(
                if (artistId != null) {
                    ArtistInfo(
                        id = artistId,
                        name = artistName,
                        isYouTubeId = isLikelyYouTubeArtistId(artistId)
                    )
                } else {
                    ArtistInfo(
                        id = "",
                        name = artistName,
                        isYouTubeId = false
                    )
                }
            )
        }

        return dedupeArtistInfos(artists)
    }

    private fun findJsonValue(jsonBlock: String, keys: List<String>): String? {
        keys.forEach { key ->
            val regex = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"")
            val value = regex.find(jsonBlock)?.groupValues?.getOrNull(1)?.trim()
            if (!value.isNullOrBlank()) {
                return value
            }
        }
        return null
    }

    private fun parseArtistIdsText(artistIdsText: String?): List<String> {
        if (artistIdsText.isNullOrBlank()) return emptyList()

        val compact = artistIdsText.trim()
        val jsonIdMatches =
            Regex("\"(?:id|artistId|artist_id|channelId|channel_id|browseId|browse_id)\"\\s*:\\s*\"([^\"]+)\"")
                .findAll(compact)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotBlank() }
                .toList()
        if (jsonIdMatches.isNotEmpty()) {
            return jsonIdMatches.distinctBy { it.lowercase() }
        }

        val quotedMatches = if (compact.startsWith("[") && compact.contains("\"")) {
            Regex("\"([^\"]+)\"")
                .findAll(compact)
                .map { it.groupValues[1].trim() }
                .filter { candidate ->
                    candidate.isNotBlank() &&
                            !candidate.equals("id", ignoreCase = true) &&
                            !candidate.equals("artistId", ignoreCase = true) &&
                            !candidate.equals("artist_id", ignoreCase = true)
                }
                .toList()
        } else {
            emptyList()
        }
        if (quotedMatches.isNotEmpty()) {
            return quotedMatches.distinctBy { it.lowercase() }
        }

        var normalized = compact
        normalized = normalized.replace(";", ",")
        normalized = normalized.replace(" & ", ",")
        normalized = normalized.replace(" / ", ",")
        normalized = normalized.removePrefix("[").removeSuffix("]").replace("\"", "")

        return normalized
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
    }

    private fun isLikelyYouTubeArtistId(artistId: String): Boolean {
        return artistId.startsWith("UC") ||
                artistId.startsWith("FEmusic_library_privately_owned_artist")
    }

    private fun enforceLocalFlagForImportedArtist(artistId: String) {
        if (isLikelyYouTubeArtistId(artistId)) return

        try {
            val writable = database.openHelper.writableDatabase
            val stmt = writable.compileStatement(
                "UPDATE artist SET isLocal = 1 WHERE id = ?"
            )
            stmt.bindString(1, artistId)
            stmt.executeUpdateDelete()
            stmt.close()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo forzar isLocal=1 para artistId=$artistId", e)
        }
    }

    private fun parseArtistsText(artistsText: String): List<String> {
        if (artistsText.isBlank()) return emptyList()

        val compact = artistsText.trim()

        val jsonNameMatches = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(compact)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (jsonNameMatches.isNotEmpty()) {
            return jsonNameMatches.distinctBy { it.lowercase() }
        }

        val jsonStringArrayMatches = if (compact.startsWith("[") && compact.contains("\"")) {
            Regex("\"([^\"]+)\"")
                .findAll(compact)
                .map { it.groupValues[1].trim() }
                .filter {
                    it.isNotBlank() && !it.equals(
                        "id",
                        ignoreCase = true
                    ) && !it.equals("name", ignoreCase = true)
                }
                .toList()
        } else {
            emptyList()
        }
        if (jsonStringArrayMatches.isNotEmpty()) {
            return jsonStringArrayMatches.distinctBy { it.lowercase() }
        }

        var normalized = compact
        normalized = normalized.replace(Regex("(?i)\\s+(feat\\.?|ft\\.?)\\s+"), ",")
        normalized = normalized.replace(";", ",")
        normalized = normalized.replace(" & ", ",")
        normalized = normalized.replace(" • ", ",")
        normalized = normalized.replace(" / ", ",")
        normalized = normalized.replace(" x ", ",")
        normalized = normalized.removePrefix("[").removeSuffix("]").replace("\"", "")

        return normalized
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
    }

    private fun findPreferredColumn(
        columns: List<String>,
        preferredNames: List<String>
    ): String? {
        if (columns.isEmpty()) return null
        val byLower = columns.associateBy { it.lowercase() }
        for (preferred in preferredNames) {
            val match = byLower[preferred.lowercase()]
            if (match != null) return match
        }
        return null
    }

    private fun parseDurationAny(rawDuration: String?): Int? {
        if (rawDuration.isNullOrBlank()) return null

        val normalized = rawDuration.trim()
        if (normalized.contains(":")) {
            return parseDuration(normalized)
        }

        val numeric = normalized.toDoubleOrNull() ?: return null
        if (numeric <= 0) return null

        // En varias bases externas la duración viene en milisegundos.
        val seconds = if (numeric > 60_000) (numeric / 1000.0) else numeric
        return seconds.toInt()
    }

    private fun parseDuration(durationText: String?): Int? {
        if (durationText == null) {
            return null
        }
        return try {
            val parts = durationText.split(":")
            when (parts.size) {
                2 -> {
                    val minutes = parts[0].toLong()
                    val seconds = parts[1].toLong()
                    ((minutes * 60 + seconds)).toInt()
                }

                3 -> {
                    val hours = parts[0].toLong()
                    val minutes = parts[1].toLong()
                    val seconds = parts[2].toLong()
                    ((hours * 3600 + minutes * 60 + seconds)).toInt()
                }

                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parseando duración: $durationText", e)
            null
        }
    }

    private fun copyUriToTempFile(uri: Uri): File? {
        Log.d(TAG, "Copiando URI a archivo temporal: $uri")
        return try {
            val tempFile = File(context.cacheDir, "import_${System.currentTimeMillis()}.db")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            tempFile
        } catch (e: Exception) {
            Log.e(TAG, "Fallo al copiar URI a archivo temporal", e)
            null
        }
    }

    // ---------- Data classes ----------
    data class ArtistInfo(
        val id: String,
        val name: String,
        val isYouTubeId: Boolean
    ) {
        companion object {
            fun fromName(name: String): ArtistInfo {
                return ArtistInfo(
                    id = ArtistEntity.generateArtistId(),
                    name = name,
                    isYouTubeId = false
                )
            }
        }
    }

    data class ImportOptions(
        val importSongs: Boolean = true,
        val importPlaylists: Boolean = true,
        val importFavorites: Boolean = true,
        val generateNewIds: Boolean = true,
        val overwriteExisting: Boolean = false
    )

    data class ImportResult(
        val songsImported: Int,
        val playlistsImported: Int,
        val favoritesImported: Int,
        val historyImported: Int,
        val queueImported: Boolean
    )

    sealed class MigrateProgress {
        object Idle : MigrateProgress()
        object CopyingFile : MigrateProgress()
        object OpeningDatabase : MigrateProgress()
        data class DetectingFork(val fork: ForkType) : MigrateProgress()
        object CleaningSessionData : MigrateProgress()
        object Done : MigrateProgress()
        data class Error(val exception: Throwable) : MigrateProgress()
    }
}

// ---------- Utilities ----------
enum class ForkType { INNERTUNE, VIMUSIC, RIMUSIC, HARMONY, UNKNOWN }

fun Cursor.getStringSafe(index: Int): String? = try {
    if (isNull(index)) {
        null
    } else {
        getString(index)
    }
} catch (e: Exception) {
    null
}

fun Cursor.getLongSafe(index: Int): Long? = try {
    if (isNull(index)) {
        null
    } else {
        getLong(index)
    }
} catch (e: Exception) {
    null
}

fun Cursor.getIntSafe(index: Int): Int? = try {
    if (isNull(index)) {
        null
    } else {
        getInt(index)
    }
} catch (e: Exception) {
    null
}

fun Cursor.getStringOrNull(columnName: String): String? {
    return try {
        val index = getColumnIndex(columnName)
        if (index != -1 && !isNull(index)) getString(index) else null
    } catch (e: Exception) {
        null
    }
}

fun Cursor.getIntOrNull(columnName: String): Int? {
    return try {
        val index = getColumnIndex(columnName)
        if (index != -1 && !isNull(index)) getInt(index) else null
    } catch (e: Exception) {
        null
    }
}