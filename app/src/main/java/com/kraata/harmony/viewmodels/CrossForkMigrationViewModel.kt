package com.kraata.harmony.viewmodels

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import com.kraata.harmony.db.MusicDatabase
import com.kraata.harmony.db.entities.SongEntity
import com.kraata.harmony.db.entities.PlaylistEntity
import com.kraata.harmony.db.entities.PlaylistSongMap
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.util.*
import javax.inject.Inject

@HiltViewModel
class CrossForkMigrationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase
) : ViewModel() {

    companion object {
        const val TAG = "Migration"
        private const val CHUNK_SIZE = 300
    }

    private val _progress = MutableStateFlow<MigrateProgress>(MigrateProgress.Idle)
    val progress: StateFlow<MigrateProgress> = _progress

    suspend fun importFromOtherFork(uri: Uri, options: ImportOptions = ImportOptions()): Result<ImportResult> {
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
                tempDb = SQLiteDatabase.openDatabase(tempFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

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
                        Log.i(TAG, "Migrando desde fork desconocido...")
                        migrateFromUnknownFork(tempDb, options)
                    }
                }

                Log.d(TAG, "Proceso de migración completado exitosamente")
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
private fun insertSongsSync(songs: List<SongEntity>, overwriteExisting: Boolean = false) {
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

    writable.beginTransaction()
    val stmt = writable.compileStatement(sql)
    try {
        for (song in songs) {
            stmt.clearBindings()

            stmt.bindString(1, song.id)
            stmt.bindString(2, song.title ?: "Unknown")

            if (song.duration >= 0) {
                stmt.bindLong(3, song.duration.toLong())
            } else {
                stmt.bindNull(3)
            }

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
                stmt.bindString(7, song.likedDate.toString())
            } else {
                stmt.bindNull(7)
            }

            if (!song.localPath.isNullOrBlank()) {
                stmt.bindString(8, song.localPath)
            } else {
                stmt.bindNull(8)
            }

            try {
                stmt.executeInsert()
            } catch (e: Exception) {
                Log.w(TAG, "Insert failed for song=${song.id}, continuing", e)
                // no throw: continuamos con siguientes canciones
            }
        }

        writable.setTransactionSuccessful()
        Log.d(TAG, "Insertadas ${songs.size} canciones exitosamente")
    } finally {
        try {
            stmt.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error cerrando statement", e)
        }
        writable.endTransaction()
    }
}

    private fun insertPlaylistsSync(playlists: List<Pair<PlaylistEntity, List<PlaylistSongMap>>>) {
        val writable = database.openHelper.writableDatabase

        for ((playlist, songMaps) in playlists) {
            writable.beginTransaction()
            try {
                // Insertar playlist
                val playlistSql = """
                    INSERT OR IGNORE INTO playlist
                    (id, name)
                    VALUES (?, ?)
                """.trimIndent()

                val playlistStmt = writable.compileStatement(playlistSql)
                playlistStmt.bindString(1, playlist.id)
                playlistStmt.bindString(2, playlist.name)
                playlistStmt.executeInsert()
                playlistStmt.close()

                // Limpiar relaciones anteriores si existen
                val deleteSql = "DELETE FROM playlist_song_map WHERE playlistId = ?"
                writable.compileStatement(deleteSql).apply {
                    bindString(1, playlist.id)
                    execute()
                    close()
                }

                // Insertar nuevas relaciones si existen
                if (songMaps.isNotEmpty()) {
                    val mapSql = """
                        INSERT OR REPLACE INTO playlist_song_map
                        (playlistId, songId, position)
                        VALUES (?, ?, ?)
                    """.trimIndent()
                    val mapStmt = writable.compileStatement(mapSql)

                    for ((index, map) in songMaps.withIndex()) {
                        mapStmt.clearBindings()
                        mapStmt.bindString(1, map.playlistId)
                        mapStmt.bindString(2, map.songId)
                        // Asegurar que la posición sea válida
                        val position = if (map.position >= 0) map.position else index
                        mapStmt.bindLong(3, position.toLong())
                        mapStmt.executeInsert()
                    }
                    mapStmt.close()
                }

                writable.setTransactionSuccessful()
                Log.d(TAG, "Playlist '${playlist.name}' insertada con ${songMaps.size} canciones")
            } catch (e: Exception) {
                Log.e(TAG, "Error insertando playlist '${playlist.name}'", e)
                // Continuar con la siguiente playlist en lugar de fallar todo
            } finally {
                writable.endTransaction()
            }
        }
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
                Log.d(TAG, "Columna '$column' ${if(found) "encontrada" else "no encontrada"} en tabla '$table'")
                found
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error verificando columna", e)
            false
        }
    }

    private suspend fun migrateFromInnerTune(sourceDb: SQLiteDatabase, options: ImportOptions): ImportResult {
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

        val playlistsToImport = if (options.importPlaylists && hasTable(sourceDb, "Playlist")) {
            Log.d(TAG, "Extrayendo playlists desde InnerTune...")
            extractPlaylistsFromInnerTune(sourceDb, options.generateNewIds)
        } else {
            Log.d(TAG, "Opción de importar playlists deshabilitada o tabla 'Playlist' no encontrada")
            emptyList()
        }

        Log.d(TAG, "Preparando inserción de ${songsToImport.size} canciones en chunks de $CHUNK_SIZE y ${playlistsToImport.size} playlists")

        var songsImported = 0
        var playlistsImported = 0
        // Mapa para conservar correspondencia entre IDs originales y nuevos (si generateNewIds)
        val idMap = mutableMapOf<String, String>()
        var favoritesImported = 0

        // Procesar canciones en chunks
        val chunks = songsToImport.chunked(CHUNK_SIZE)
        var totalChunks = if (songsToImport.isEmpty()) 0 else (songsToImport.size / CHUNK_SIZE) + 1

        chunks.forEachIndexed { idx, chunk ->
            Log.i(TAG, "Procesando chunk ${idx + 1}/${chunks.size} (${chunk.size} canciones)")

            // Aplicar generateNewIds si es necesario y registrar el mapeo oldId -> newId
           val songsToInsert = chunk.map { song ->
    val originalId = song.id
    val shouldGenerate = options.generateNewIds && songExists(originalId)
    if (shouldGenerate) {
        val newId = UUID.randomUUID().toString()
        idMap[originalId] = newId
        song.copy(id = newId)
    } else {
        // Preserve original id (and don't create idMap entry)
        song
    }
}


            // Contar favoritos
            favoritesImported += songsToInsert.count { it.liked }

            // Insertar sincrónicamente
            insertSongsSync(songsToInsert)
            songsImported += songsToInsert.size

            Log.i(TAG, "Chunk ${idx + 1} completado. Total insertadas: $songsImported")
        }

        // Log ID mappings (old -> new) to inspect original YouTube IDs
        try {
            logIdMappings(idMap)
        } catch (e: Exception) {
            Log.w(TAG, "Error logging id mappings", e)
        }

        // Procesar playlists sincrónicamente
        if (playlistsToImport.isNotEmpty()) {
            Log.d(TAG, "Insertando ${playlistsToImport.size} playlists...")
            // Si generamos nuevos IDs para canciones, remappear songId en los mapas de playlist
            val remappedPlaylists = if (idMap.isNotEmpty()) {
                playlistsToImport.map { (pl, maps) ->
                    val remappedMaps = maps.map { m ->
                        val newSongId = idMap[m.songId] ?: m.songId
                        PlaylistSongMap(playlistId = pl.id, songId = newSongId, position = m.position)
                    }
                    Pair(pl, remappedMaps)
                }
            } else playlistsToImport

            insertPlaylistsSync(remappedPlaylists)
            playlistsImported = playlistsToImport.size
        }

        // Remap favorites stored en tablas separadas (si existen en la fuente)
        try {
            remapFavoritesInTarget(sourceDb, idMap)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron remapear favoritos", e)
        }

        Log.d(TAG, "Transacción completada: $songsImported canciones, $playlistsImported playlists, $favoritesImported favoritos")

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

            // Agregar columnas disponibles
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
                            val dur = c.getIntOrNull("duration")
                            if (dur != null && dur >= 0) dur.toLong() else -1L
                        } else -1L

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
                        // Continuar con la siguiente fila
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

    private fun extractPlaylistsFromInnerTune(sourceDb: SQLiteDatabase, generateNewIds: Boolean): List<Pair<PlaylistEntity, List<PlaylistSongMap>>> {
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
                                    songMaps.add(PlaylistSongMap(playlistId = newId, songId = songId, position = pos))
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

    private suspend fun migrateFromViMusic(sourceDb: SQLiteDatabase, options: ImportOptions): ImportResult {
        Log.d(TAG, "Iniciando migración desde ViMusic con opciones: $options")

        // Loguear estructura inicial para diagnóstico
        logTablePreview(sourceDb, "songs", 5)
        logTablePreview(sourceDb, "playlists", 5)

        val songsToImport = if (options.importSongs && hasTable(sourceDb, "songs")) {
            Log.d(TAG, "Extrayendo canciones desde ViMusic...")
            extractSongsFromViMusic(sourceDb)
        } else {
            Log.d(TAG, "Opción de importar canciones deshabilitada o tabla 'songs' no encontrada")
            emptyList()
        }

        val playlistsToImport = if (options.importPlaylists && hasTable(sourceDb, "playlists")) {
            Log.d(TAG, "Extrayendo playlists desde ViMusic...")
            extractPlaylistsFromViMusic(sourceDb, options.generateNewIds)
        } else {
            Log.d(TAG, "Opción de importar playlists deshabilitada o tabla 'playlists' no encontrada")
            emptyList()
        }

        Log.d(TAG, "Preparando inserción de ${songsToImport.size} canciones en chunks de $CHUNK_SIZE y ${playlistsToImport.size} playlists")

        var songsImported = 0
        var playlistsImported = 0
        // Mapa para conservar correspondencia entre IDs originales y nuevos (si generateNewIds)
        val idMap = mutableMapOf<String, String>()

        // Procesar canciones en chunks
        val chunks = songsToImport.chunked(CHUNK_SIZE)
        var totalChunks = if (songsToImport.isEmpty()) 0 else (songsToImport.size / CHUNK_SIZE) + 1

        chunks.forEachIndexed { idx, chunk ->
            Log.i(TAG, "Procesando chunk ${idx + 1}/${chunks.size} (${chunk.size} canciones)")

            // Aplicar generateNewIds si es necesario y registrar el mapeo oldId -> newId
            val songsToInsert = if (options.generateNewIds) {
                chunk.map { song ->
                    val newId = UUID.randomUUID().toString()
                    idMap[song.id] = newId
                    song.copy(id = newId)
                }
            } else {
                chunk
            }

            // Insertar sincrónicamente
            insertSongsSync(songsToInsert)
            songsImported += songsToInsert.size

            Log.i(TAG, "Chunk ${idx + 1} completado. Total insertadas: $songsImported")
        }

            // Log ID mappings (old -> new) to inspect original YouTube IDs
            try {
                logIdMappings(idMap)
            } catch (e: Exception) {
                Log.w(TAG, "Error logging id mappings ViMusic", e)
            }

        // Procesar playlists sincrónicamente
        if (playlistsToImport.isNotEmpty()) {
            Log.d(TAG, "Insertando ${playlistsToImport.size} playlists...")
            val remappedPlaylists = if (idMap.isNotEmpty()) {
                playlistsToImport.map { (pl, maps) ->
                    val remappedMaps = maps.map { m ->
                        val newSongId = idMap[m.songId] ?: m.songId
                        PlaylistSongMap(playlistId = pl.id, songId = newSongId, position = m.position)
                    }
                    Pair(pl, remappedMaps)
                }
            } else playlistsToImport

            insertPlaylistsSync(remappedPlaylists)
            playlistsImported = playlistsToImport.size
        }

        // Remap favorites
        try {
            remapFavoritesInTarget(sourceDb, idMap)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron remapear favoritos ViMusic", e)
        }

        Log.d(TAG, "Transacción ViMusic completada: $songsImported canciones, $playlistsImported playlists")

        val result = ImportResult(
            songsImported = songsImported,
            playlistsImported = playlistsImported,
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
            sourceDb.rawQuery(
                "SELECT id, title, artistsText, albumTitle, durationText, thumbnailUrl FROM songs",
                null
            ).use { c ->
                Log.d(TAG, "Consulta ViMusic ejecutada, procesando resultados...")
                var count = 0
                while (c.moveToNext()) {
                    try {
                        // Saneamiento de ID
                        val id = c.getStringOrNull("id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()

                        // Saneamiento de título
                        val title = c.getStringOrNull("title")?.takeIf { it.isNotBlank() } ?: "Unknown"

                        // Saneamiento de artista (opcional)
                        val artist = c.getStringOrNull("artistsText")?.takeIf { it.isNotBlank() }

                        // Saneamiento de álbum (opcional)
                        val album = c.getStringOrNull("albumTitle")?.takeIf { it.isNotBlank() }

                        // Saneamiento de duración (texto -> segundos)
                        val durationText = c.getStringOrNull("durationText")
                        val durationSeconds = parseDuration(durationText)

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

    private fun extractPlaylistsFromViMusic(sourceDb: SQLiteDatabase, generateNewIds: Boolean): List<Pair<PlaylistEntity, List<PlaylistSongMap>>> {
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
                                    songMaps.add(PlaylistSongMap(playlistId = newId, songId = songId, position = pos))
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

    private suspend fun migrateFromRiMusic(sourceDb: SQLiteDatabase, options: ImportOptions): ImportResult {
        Log.d(TAG, "Migrando desde RiMusic (usando lógica de ViMusic)...")
        return migrateFromViMusic(sourceDb, options)
    }

    private suspend fun migrateFromHarmony(sourceDb: SQLiteDatabase, options: ImportOptions): ImportResult {
        Log.d(TAG, "Migrando desde Harmony (usando lógica de InnerTune)...")
        return migrateFromInnerTune(sourceDb, options)
    }

    private suspend fun migrateFromUnknownFork(sourceDb: SQLiteDatabase, options: ImportOptions): ImportResult {
        Log.i(TAG, "Intentando migración genérica para fork desconocido con opciones: $options")

        val allTables = mutableListOf<String>()
        sourceDb.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
            Log.d(TAG, "Obteniendo todas las tablas disponibles...")
            while (c.moveToNext()) allTables.add(c.getString(0))
        }
        Log.i(TAG, "Tablas disponibles: $allTables")

        // Logear preview de tablas clave
        logTablePreview(sourceDb, allTables.firstOrNull { it.lowercase() == "song" || it.lowercase() == "songs" } ?: "song", 5)
        logTablePreview(sourceDb, allTables.firstOrNull { it.lowercase() == "playlist" || it.lowercase() == "playlists" } ?: "playlist", 5)

        val songsToImport = if (options.importSongs) {
            Log.d(TAG, "Extrayendo canciones genéricas...")
            extractGenericSongs(sourceDb, allTables)
        } else {
            Log.d(TAG, "Opción de importar canciones deshabilitada para migración genérica")
            emptyList()
        }

        val playlistsToImport = if (options.importPlaylists) {
            Log.d(TAG, "Extrayendo playlists genéricas...")
            extractGenericPlaylists(sourceDb, allTables, options.generateNewIds)
        } else {
            Log.d(TAG, "Opción de importar playlists deshabilitada para migración genérica")
            emptyList()
        }

        Log.d(TAG, "Preparando inserción genérica de ${songsToImport.size} canciones en chunks de $CHUNK_SIZE y ${playlistsToImport.size} playlists")

        var songsImported = 0
        var playlistsImported = 0
        var favoritesImported = 0
        val idMap = mutableMapOf<String, String>()

        // Procesar canciones en chunks
        val chunks = songsToImport.chunked(CHUNK_SIZE)
        var totalChunks = if (songsToImport.isEmpty()) 0 else (songsToImport.size / CHUNK_SIZE) + 1

        chunks.forEachIndexed { idx, chunk ->
            Log.i(TAG, "Procesando chunk ${idx + 1}/${chunks.size} (${chunk.size} canciones)")

            // Aplicar generateNewIds si es necesario y registrar el mapeo oldId -> newId
            val songsToInsert = if (options.generateNewIds) {
                chunk.map { song ->
                    val newId = UUID.randomUUID().toString()
                    idMap[song.id] = newId
                    song.copy(id = newId)
                }
            } else {
                chunk
            }

            // Contar favoritos
            favoritesImported += songsToInsert.count { it.liked }

            // Insertar sincrónicamente
            insertSongsSync(songsToInsert)
            songsImported += songsToInsert.size

            Log.i(TAG, "Chunk ${idx + 1} completado. Total insertadas: $songsImported")
        }

            // Log ID mappings (old -> new) to inspect original YouTube IDs
            try {
                logIdMappings(idMap)
            } catch (e: Exception) {
                Log.w(TAG, "Error logging id mappings (generic)", e)
            }
        // Procesar playlists sincrónicamente
        if (playlistsToImport.isNotEmpty()) {
            Log.d(TAG, "Insertando ${playlistsToImport.size} playlists...")
            val remappedPlaylists = if (idMap.isNotEmpty()) {
                playlistsToImport.map { (pl, maps) ->
                    val remappedMaps = maps.map { m ->
                        val newSongId = idMap[m.songId] ?: m.songId
                        PlaylistSongMap(playlistId = pl.id, songId = newSongId, position = m.position)
                    }
                    Pair(pl, remappedMaps)
                }
            } else playlistsToImport

            insertPlaylistsSync(remappedPlaylists)
            playlistsImported = playlistsToImport.size
        }

        Log.d(TAG, "Transacción genérica completada: $songsImported canciones, $playlistsImported playlists, $favoritesImported favoritos")

        val result = ImportResult(
            songsImported = songsImported,
            playlistsImported = playlistsImported,
            favoritesImported = favoritesImported,
            historyImported = 0,
            queueImported = false
        )

        Log.i(TAG, "Migración genérica completada: $result")
        return result
    }

    private fun extractGenericSongs(sourceDb: SQLiteDatabase, allTables: List<String>): List<SongEntity> {
        Log.d(TAG, "Extrayendo canciones genéricas desde tablas: $allTables")
        val songs = mutableListOf<SongEntity>()

        val songTable = allTables.firstOrNull { it.lowercase() == "song" || it.lowercase() == "songs" }
        if (songTable != null) {
            Log.d(TAG, "Tabla de canciones encontrada: $songTable")
            try {
                val columns = getTableColumns(sourceDb, songTable)
                Log.d(TAG, "Columnas de la tabla '$songTable': $columns")

                val idCol = columns.firstOrNull { it.lowercase() == "id" }
                val titleCol = columns.firstOrNull { it.lowercase() in listOf("title", "name") }
                val durationCol = columns.firstOrNull { it.lowercase() in listOf("duration", "durationtext") }
                val thumbCol = columns.firstOrNull { it.lowercase() in listOf("thumbnailurl", "thumbnail", "image") }
                val likedCol = columns.firstOrNull { it.lowercase() in listOf("liked", "isfavorite", "favorite") }
                val albumCol = columns.firstOrNull { it.lowercase() in listOf("albumname", "album") }

                Log.d(TAG, "Columnas identificadas - id: $idCol, title: $titleCol, duration: $durationCol, thumb: $thumbCol, liked: $likedCol, album: $albumCol")

                if (idCol != null && titleCol != null) {
                    // Build SELECT with exact order
                    val selectCols = listOfNotNull(idCol, titleCol, durationCol, thumbCol, albumCol, likedCol).joinToString(", ")
                    val query = "SELECT $selectCols FROM $songTable"
                    Log.d(TAG, "Ejecutando consulta genérica: $query")

                    sourceDb.rawQuery(query, null).use { c ->
                        Log.d(TAG, "Encontradas ${c.count} filas en $songTable")
                        var extractedCount = 0

                        while (c.moveToNext()) {
                            try {
                                // Use sequential indices based on SELECT order
                                var colIdx = 0

                                val id = c.getStringSafe(colIdx++) ?: UUID.randomUUID().toString()
                                val title = c.getStringSafe(colIdx++) ?: "Unknown"

                                val duration = if (durationCol != null) {
                                    val value = c.getStringSafe(colIdx++)
                                    when {
                                        value == null -> {
                                            -1
                                        }
                                        value.contains(":") -> {
                                            parseDuration(value) ?: -1
                                        }
                                        else -> {
                                            value.toIntOrNull() ?: -1
                                        }
                                    }
                                } else -1

                                val thumbnail = if (thumbCol != null) c.getStringSafe(colIdx++) else null
                                val album = if (albumCol != null) c.getStringSafe(colIdx++) else null
                                val liked = if (likedCol != null) c.getIntSafe(colIdx++) == 1 else false

                                val song = SongEntity(
                                    id = id,
                                    title = title,
                                    duration = duration,
                                    thumbnailUrl = thumbnail,
                                    albumName = album,
                                    liked = liked,
                                    likedDate = if (liked) LocalDateTime.now() else null,
                                    localPath = null
                                )

                                songs.add(song)
                                extractedCount++

                                if (extractedCount % 500 == 0) {
                                    Log.d(TAG, "Canciones genéricas extraídas: $extractedCount")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Fallo al extraer canción genérica en fila $extractedCount", e)
                            }
                        }
                        Log.d(TAG, "Extracción genérica completada: $extractedCount canciones")
                    }
                } else {
                    Log.w(TAG, "No se encontraron columnas ID o Title en la tabla de canciones")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error extrayendo desde tabla de canciones", e)
            }
        } else {
            Log.w(TAG, "No se encontró tabla de canciones para migración genérica")
        }

        Log.d(TAG, "Finalizada extracción de canciones genéricas: ${songs.size} canciones")
        return songs
    }

    private fun extractGenericPlaylists(sourceDb: SQLiteDatabase, allTables: List<String>, generateNewIds: Boolean): List<Pair<PlaylistEntity, List<PlaylistSongMap>>> {
        Log.d(TAG, "Extrayendo playlists genéricas (generar nuevos IDs: $generateNewIds), tablas disponibles: $allTables")
        val playlists = mutableListOf<Pair<PlaylistEntity, List<PlaylistSongMap>>>()

        val playlistTable = allTables.firstOrNull { it.lowercase() == "playlist" || it.lowercase() == "playlists" }
        if (playlistTable != null) {
            Log.d(TAG, "Tabla de playlists encontrada: $playlistTable")
            try {
                val columns = getTableColumns(sourceDb, playlistTable)
                Log.d(TAG, "Columnas de la tabla '$playlistTable': $columns")

                val idCol = columns.firstOrNull { it.lowercase() == "id" }
                val nameCol = columns.firstOrNull { it.lowercase() in listOf("name", "title") }
                val browseIdCol = columns.firstOrNull { it.lowercase() == "browseid" }

                Log.d(TAG, "Columnas identificadas - id: $idCol, name: $nameCol, browseId: $browseIdCol")

                if (idCol != null && nameCol != null) {
                    // Only import local playlists (those without browseId or with empty browseId)
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
                                val playlistId = c.getStringOrNull(idCol) ?: UUID.randomUUID().toString()
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
                                    it.lowercase() in listOf("playlistsongmap", "playlist_song_map", "songplaylistmap", "song_playlist_map")
                                }

                                if (mapTable != null) {
                                    try {
                                        sourceDb.rawQuery(
                                            "SELECT songId, position FROM $mapTable WHERE playlistId = ? ORDER BY position",
                                            arrayOf(playlistId)
                                        ).use { sc ->
                                            while (sc.moveToNext()) {
                                                val songId = sc.getStringOrNull("songId") ?: continue
                                                val pos = sc.getIntOrNull("position") ?: 0
                                                songMaps.add(PlaylistSongMap(playlistId = newId, songId = songId, position = pos))
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Error extrayendo canciones para playlist genérica '$playlistName'", e)
                                    }
                                }

                                playlists.add(Pair(playlist, songMaps))
                                extractedCount++

                                if (extractedCount % 50 == 0) {
                                    Log.d(TAG, "Playlists genéricas extraídas: $extractedCount")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Fallo al extraer playlist genérica en fila $extractedCount", e)
                            }
                        }
                        Log.d(TAG, "Extracción genérica de playlists completada: $extractedCount playlists")
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

    private fun songExists(id: String): Boolean {
        return try {
            val db = database.openHelper.readableDatabase

            val cursor = db.query(
                "SELECT 1 FROM song WHERE id = ? LIMIT 1",
                arrayOf(id)
            )

            cursor.use {
                it.moveToFirst()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking songExists($id)", e)
            false
        }
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
                        val value = try { c.getString(c.getColumnIndexOrThrow(col)) } catch (e: Exception) { null }
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

        val candidates = listOf("favorites", "favourites", "liked_songs", "liked", "favorite_songs", "favorite", "bookmarks", "bookmark")
        val writable = database.openHelper.writableDatabase

        for (table in candidates) {
            if (!hasTable(sourceDb, table)) continue

            val cols = getTableColumns(sourceDb, table)
            val idCol = cols.firstOrNull { it.equals("songId", true) || it.equals("song_id", true) || it.equals("id", true) || it.equals("trackId", true) || it.equals("track_id", true) }
            if (idCol == null) {
                Log.d(TAG, "Tabla $table encontrada pero no se identificó columna de songId: $cols")
                continue
            }

            Log.i(TAG, "Remapeando favoritos desde tabla '$table' (columna $idCol)")
            var total = 0
            var updated = 0
            sourceDb.rawQuery("SELECT $idCol FROM $table", null).use { c ->
                while (c.moveToNext()) {
                    val oldId = try { c.getString(c.getColumnIndexOrThrow(idCol)) } catch (e: Exception) { null }
                    if (oldId.isNullOrBlank()) continue
                    total++
                    val newId = idMap[oldId]
                    if (newId != null) {
                        try {
                            val stmt = writable.compileStatement("UPDATE song SET liked = 1, likedDate = ? WHERE id = ?")
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
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(tableName)).use { c ->
                c.count > 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error verificando tabla $tableName", e)
            false
        }
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
        object Done : MigrateProgress()
        data class Error(val exception: Throwable) : MigrateProgress()
    }
}

// ---------- Utilities ----------
enum class ForkType { INNERTUNE, VIMUSIC, RIMUSIC, HARMONY, UNKNOWN }

// Cursor helpers
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

// Nuevas extensiones para manejo seguro de columnas por nombre
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

fun Cursor.getLongOrNull(columnName: String): Long? {
    return try {
        val index = getColumnIndex(columnName)
        if (index != -1 && !isNull(index)) getLong(index) else null
    } catch (e: Exception) {
        null
    }
}