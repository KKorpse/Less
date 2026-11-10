package com.kkorpse.less.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kkorpse.less.LessApp
import com.kkorpse.less.R
import com.kkorpse.less.data.MediaItem
import com.kkorpse.less.data.MediaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class VideoFeedState(
    val loading: Boolean = true,
    val videos: List<MediaItem> = emptyList(),
    val error: String? = null
)

data class VideoUndoEntry(
    val item: MediaItem,
    val index: Int
)

class VideoFeedViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as LessApp
    private val repository = MediaRepository(application)

    private val _state = MutableStateFlow(VideoFeedState())
    val state = _state.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo = _canUndo.asStateFlow()

    private val undoStack = ArrayDeque<VideoUndoEntry>()

    var currentIndex: Int = 0
        private set

    private var initialized = false

    fun initialize(sessionItems: List<MediaItem>, startIndex: Int) {
        if (initialized) return
        initialized = true
        if (sessionItems.isNotEmpty()) {
            _state.value = VideoFeedState(loading = false, videos = sessionItems)
            currentIndex = startIndex.coerceIn(0, (sessionItems.size - 1).coerceAtLeast(0))
        } else {
            load()
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            repository.loadVideos()
                .onSuccess { videos ->
                    _state.value = VideoFeedState(loading = false, videos = videos)
                    currentIndex = currentIndex.coerceIn(0, (videos.size - 1).coerceAtLeast(0))
                }
                .onFailure {
                    _state.value = VideoFeedState(
                        loading = false,
                        error = getApplication<Application>().getString(R.string.error_generic)
                    )
                }
        }
    }

    fun setCurrentIndex(index: Int) {
        currentIndex = index
    }

    fun peekUndo(): VideoUndoEntry? = undoStack.lastOrNull()

    fun confirmTrash(item: MediaItem, index: Int) {
        app.browseStore.onTrashed(true)
        undoStack.addLast(VideoUndoEntry(item, index))
        _canUndo.value = true

        val next = _state.value.videos.filterNot { it.uri == item.uri }
        _state.value = _state.value.copy(videos = next)
        currentIndex = currentIndex.coerceAtMost((next.size - 1).coerceAtLeast(0))
    }

    fun confirmRestore(item: MediaItem, index: Int) {
        app.browseStore.onRestored(true)

        val videos = _state.value.videos.toMutableList()
        val insertAt = index.coerceIn(0, videos.size)
        videos.add(insertAt, item)
        _state.value = _state.value.copy(videos = videos)
        currentIndex = insertAt

        if (undoStack.isNotEmpty()) undoStack.removeLast()
        _canUndo.value = undoStack.isNotEmpty()
    }
}
