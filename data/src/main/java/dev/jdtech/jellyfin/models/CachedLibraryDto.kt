package dev.jdtech.jellyfin.models

import androidx.room3.Entity
import androidx.room3.Index
import java.util.UUID

@Entity(tableName = "cachedLibraries", primaryKeys = ["serverId", "id"], indices = [Index("serverId")])
data class CachedLibraryDto(
    val serverId: String,
    val id: UUID,
    val name: String,
    val collectionType: String?,
    val primaryImageTag: String?,
)
