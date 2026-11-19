package dev.jdtech.jellyfin.models

import androidx.room3.Entity
import java.util.UUID

@Entity(tableName = "cachedPeople", primaryKeys = ["serverId", "id"])
data class CachedPersonDto(
    val serverId: String,
    val id: UUID,
    val name: String,
    val overview: String,
    val primaryImageTag: String?,
)
