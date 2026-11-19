package dev.jdtech.jellyfin.film.presentation.home

import dev.jdtech.jellyfin.models.FindroidItem

data class GenreState(
    val items: List<FindroidItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: Exception? = null,
)
