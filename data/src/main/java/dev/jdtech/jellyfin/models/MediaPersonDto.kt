package dev.jdtech.jellyfin.models

import androidx.room3.Entity
import androidx.room3.Index
import java.util.UUID

/** A lightweight snapshot of a person credit supplied with a Jellyfin item. */
@Entity(
    tableName = "mediaPeople",
    primaryKeys = ["serverId", "itemId", "personId", "role"],
    indices = [Index(value = ["serverId", "itemId"])],
)
data class MediaPersonDto(
    val serverId: String,
    val itemId: UUID,
    val personId: UUID,
    val name: String,
    val type: String,
    val role: String,
    val primaryImageTag: String?,
)
