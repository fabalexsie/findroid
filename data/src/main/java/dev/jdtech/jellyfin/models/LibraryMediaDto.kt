package dev.jdtech.jellyfin.models

import androidx.room3.Entity
import androidx.room3.Index
import java.util.UUID

@Entity(
    tableName = "libraryMedia",
    primaryKeys = ["serverId", "libraryId", "itemId"],
    indices = [Index(value = ["serverId", "libraryId"])],
)
data class LibraryMediaDto(
    val serverId: String,
    val libraryId: UUID,
    val itemId: UUID,
)
