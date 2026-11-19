package dev.jdtech.jellyfin.models

import androidx.room3.Entity
import androidx.room3.Index
import java.util.UUID

/**
 * Full Jellyfin DTO snapshot. The typed movie/show/episode tables support the current UI while
 * this JSON snapshot retains every field returned by the SDK for future offline detail screens.
 */
@Entity(
    tableName = "cachedJellyfinItems",
    primaryKeys = ["serverId", "itemId"],
    indices = [Index(value = ["serverId"])],
)
data class CachedJellyfinItemDto(
    val serverId: String,
    val itemId: UUID,
    val payload: String,
    val syncedAt: Long,
)
