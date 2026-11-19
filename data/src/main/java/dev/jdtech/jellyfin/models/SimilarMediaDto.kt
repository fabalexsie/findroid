package dev.jdtech.jellyfin.models

import androidx.room3.Entity
import androidx.room3.Index
import java.util.UUID

/** Ordered Jellyfin recommendation edge, cached independently from local video files. */
@Entity(
    tableName = "similarMedia",
    primaryKeys = ["serverId", "itemId", "similarItemId"],
    indices = [Index(value = ["serverId", "itemId", "sortOrder"])],
)
data class SimilarMediaDto(
    val serverId: String,
    val itemId: UUID,
    val similarItemId: UUID,
    val sortOrder: Int,
)
