package dev.jdtech.jellyfin.models

import androidx.room3.Entity
import androidx.room3.Index
import java.util.UUID

/** A normalized link between a cached Jellyfin item and one of its genres. */
@Entity(
    tableName = "mediaGenres",
    primaryKeys = ["serverId", "itemId", "genre"],
    indices = [Index(value = ["serverId", "genre"]), Index(value = ["itemId"])],
)
data class MediaGenreDto(
    val serverId: String,
    val itemId: UUID,
    val genre: String,
)
