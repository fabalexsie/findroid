package dev.jdtech.jellyfin.presentation.film

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.presentation.home.GenreViewModel
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.presentation.film.components.Direction
import dev.jdtech.jellyfin.presentation.film.components.ItemCard
import dev.jdtech.jellyfin.presentation.theme.spacings

@Composable
fun GenreScreen(
    genre: String,
    onItemClick: (FindroidItem) -> Unit,
    navigateBack: () -> Unit,
    viewModel: GenreViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(genre) { viewModel.load(genre) }

    GenreScreenLayout(
        genre = genre,
        items = state.items,
        isLoading = state.isLoading,
        onItemClick = onItemClick,
        navigateBack = navigateBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenreScreenLayout(
    genre: String,
    items: List<FindroidItem>,
    isLoading: Boolean,
    onItemClick: (FindroidItem) -> Unit,
    navigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(genre) },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(painterResource(CoreR.drawable.ic_arrow_left), contentDescription = null)
                    }
                },
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(MaterialTheme.spacings.default),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
            ) {
                items(items, key = { it.id }) { item ->
                    ItemCard(item = item, direction = Direction.VERTICAL, onClick = onItemClick)
                }
            }
        }
    }
}
