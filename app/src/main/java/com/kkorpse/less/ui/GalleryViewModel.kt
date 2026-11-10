package com.kkorpse.less.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kkorpse.less.R
import com.kkorpse.less.data.MediaItem
import com.kkorpse.less.data.MediaRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MediaFilter {
    ALL, PHOTOS, VIDEOS
}

data class GalleryUiState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val items: List<MediaItem> = emptyList(),
    val filter: MediaFilter = MediaFilter.ALL,
    val error: String? = null
) {
    val filteredItems: List<MediaItem>
        get() = when (filter) {
            MediaFilter.ALL -> items
            MediaFilter.PHOTOS -> items.filter { !it.isVideo }
            MediaFilter.VIDEOS -> items.filter { it.isVideo }
        }

    val photoCount: Int get() = items.count { !it.isVideo }
    val videoCount: Int get() = items.count { it.isVideo }
}

sealed class ViewerRequest {
    data class Library(val items: List<MediaItem>, val index: Int) : ViewerRequest()
    data class Picked(val items: List<MediaItem>) : ViewerRequest()
}

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)
    private val _state = MutableStateFlow(GalleryUiState())
    val state = _state.asStateFlow()

    private val _openViewer = MutableSharedFlow<ViewerRequest>(extraBufferCapacity = 1)
    val openViewer = _openViewer.asSharedFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages = _messages.asSharedFlow()

    private var loadJob: Job? = null

    fun loadLibrary() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            repository.loadLibrary()
                .onSuccess { items ->
                    _state.update {
                        it.copy(loading = false, loaded = true, items = items, error = null)
                    }
                }
                .onFailure { throwable ->
                    _state.update {
                        it.copy(
                            loading = false,
                            loaded = true,
                            error = messageFor(throwable)
                        )
                    }
                }
        }
    }

    fun openPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        loadJob?.cancel()
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            repository.loadPicked(uris)
                .onSuccess { items ->
                    _state.update { it.copy(loading = false) }
                    if (items.isEmpty()) {
                        _messages.tryEmit(getApplication<Application>().getString(R.string.empty_picked_message))
                    } else {
                        _openViewer.tryEmit(ViewerRequest.Picked(items))
                    }
                }
                .onFailure { throwable ->
                    _state.update { it.copy(loading = false) }
                    _messages.tryEmit(messageFor(throwable))
                }
        }
    }

    fun openLibrary(index: Int) {
        val items = _state.value.filteredItems
        if (index !in items.indices) return
        _openViewer.tryEmit(ViewerRequest.Library(items, index))
    }

    fun setFilter(filter: MediaFilter) {
        _state.update { it.copy(filter = filter) }
    }

    fun consumeError() {
        _state.update { it.copy(error = null) }
    }

    private fun messageFor(throwable: Throwable): String {
        val app = getApplication<Application>()
        return when (throwable) {
            is SecurityException -> app.getString(R.string.permission_denied_message)
            else -> app.getString(R.string.error_generic)
        }
    }
}
