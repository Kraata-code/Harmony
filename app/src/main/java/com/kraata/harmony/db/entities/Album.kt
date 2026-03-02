/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 * Copyright (C) 2026 Harmony Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.kraata.harmony.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

@Immutable
data class Album(
    @Embedded
    val album: AlbumEntity,
    val downloadCount: Int,
    @Relation(
        entity = ArtistEntity::class,
        entityColumn = "id",
        parentColumn = "id",
        associateBy = Junction(
            value = AlbumArtistMap::class,
            parentColumn = "albumId",
            entityColumn = "artistId"
        )
    )
    val artists: List<ArtistEntity>,
) : LocalItem() {
    override val id: String
        get() = album.id
    override val title: String
        get() = album.title
    override val thumbnailUrl: String?
        get() = album.thumbnailUrl
}
