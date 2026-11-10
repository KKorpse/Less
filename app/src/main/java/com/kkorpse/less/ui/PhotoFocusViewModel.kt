package com.kkorpse.less.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kkorpse.less.LessApp
import com.kkorpse.less.data.MediaItem
import com.kkorpse.less.data.MediaRepository
import com.kkorpse.less.data.PhotoGroupBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PhotoUndoEntry(
    val item: MediaItem,
    val index: Int
)

class PhotoFocusViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as LessApp
    private val builder = PhotoGroupBuilder(
        MediaRepository(application),
        app.preferences,
        app.browseStore
    )

    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())
    val items = _items.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo = _canUndo.asStateFlow()

    private val undoStack = ArrayDeque<PhotoUndoEntry>()
    private val removedUris = mutableSetOf<String>()

    var currentIndex: Int = 0
    var canTrash: Boolean = true
        private set

    private var initialized = false
    private var appending = false
    private var noMore = false
    private var autoAppend = true

    fun initialize(
        items: List<MediaItem>,
        startIndex: Int,
        canTrash: Boolean,
        autoAppend: Boolean
    ) {
        if (initialized) return
        initialized = true
        _items.value = items
        currentIndex = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        this.canTrash = canTrash
        this.autoAppend = autoAppend
    }

    fun appendIfNeeded(position: Int) {
        if (!initialized || !autoAppend || appending || noMore) return
        val current = _items.value
        if (position < current.size - 3) return

        appending = true
        viewModelScope.launch {
            builder.appendGroup(current)
                .onSuccess { group ->
                    val existing = _items.value.map { it.uri.toString() }.toSet()
                    val added = group.filter {
                        val key = it.uri.toString()
                        key !in existing && key !in removedUris
                    }
                    if (added.isEmpty()) {
                        noMore = true
                    } else {
                        _items.value = _items.value + added
                    }
                }
                .onFailure {
                    noMore = true
                }
            appending = false
        }
    }

    fun peekUndo(): PhotoUndoEntry? = undoStack.lastOrNull()

    fun confirmTrash(item: MediaItem, index: Int) {
        removedUris += item.uri.toString()
        app.browseStore.onTrashed(false)
        undoStack.addLast(PhotoUndoEntry(item, index))
        _canUndo.value = true

        val next = _items.value.filterNot { it.uri == item.uri }
        _items.value = next
        currentIndex = currentIndex.coerceAtMost((next.size - 1).coerceAtLeast(0))
    }

    fun confirmRestore(item: MediaItem, index: Int) {
        removedUris -= item.uri.toString()
        app.browseStore.onRestored(false)

        val next = _items.value.toMutableList()
        val insertAt = index.coerceIn(0, next.size)
        next.add(insertAt, item)
        _items.value = next
        currentIndex = insertAt

        if (undoStack.isNotEmpty()) undoStack.removeLast()
        _canUndo.value = undoStack.isNotEmpty()
    }
}
