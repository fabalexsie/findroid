package dev.jdtech.jellyfin.film.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.repository.JellyfinRepository
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class GenreViewModel @Inject constructor(private val repository: JellyfinRepository) : ViewModel() {
    private val _state = MutableStateFlow(GenreState())
    val state = _state.asStateFlow()

    fun load(genre: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.emit(_state.value.copy(isLoading = true, error = null))
            try {
                _state.emit(_state.value.copy(items = repository.getGenreItems(genre), isLoading = false))
            } catch (e: Exception) {
                _state.emit(_state.value.copy(isLoading = false, error = e))
            }
        }
    }
}
