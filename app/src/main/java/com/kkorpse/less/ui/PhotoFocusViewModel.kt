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

class PhotoFocusViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as LessApp
    private val groupBuilder = PhotoGroupBuilder(
        MediaRepository(application),
        app.preferences,
        app.browseStore
    )

    private val photoPages = mutableListOf<FocusPage.Photo>()
    private val pending = mutableListOf<PendingDelete>()
    private val deleted = mutableListOf<PendingDelete>()
    private var busy = false

    private val _pages = MutableStateFlow<List<FocusPage>>(emptyList())
    val pages = _pages.asStateFlow()

    var groupSize: Int = 0
        private set

    var currentIndex: Int = 0
    var groupGeneration: Int = 0
        private set

    private var initialized = false
    var autoNextGroup: Boolean = true
        private set
    private var loadingNext = false

    fun initialize(items: List<MediaItem>, startIndex: Int, autoNextGroup: Boolean) {
        if (initialized) return
        initialized = true
        this.autoNextGroup = autoNextGroup
        groupSize = items.size
        items.forEachIndexed { index, item ->
            photoPages += FocusPage.Photo(item, index)
        }
        currentIndex = startIndex.coerceIn(0, items.lastIndex.coerceAtLeast(0))
        emit()
    }

    fun loadNextGroup() {
        if (!autoNextGroup || loadingNext) return
        loadingNext = true
        busy = true
        emit()
        viewModelScope.launch {
            groupBuilder.newGroup()
                .onSuccess { photos ->
                    if (photos.isEmpty()) {
                        loadingNext = false
                        busy = false
                        emit()
                        return@onSuccess
                    }
                    photoPages.clear()
                    pending.clear()
                    deleted.clear()
                    groupSize = photos.size
                    photos.forEachIndexed { index, item ->
                        photoPages += FocusPage.Photo(item, index)
                    }
                    currentIndex = 0
                    groupGeneration++
                    loadingNext = false
                    busy = false
                    emit()
                }
                .onFailure {
                    loadingNext = false
                    busy = false
                    autoNextGroup = false
                    emit()
                }
        }
    }

    fun markForDeletion(page: FocusPage.Photo): Boolean {
        if (deleted.isNotEmpty()) return false
        val index = photoPages.indexOfFirst { it.item.uri == page.item.uri }
        if (index == -1) return false
        photoPages.removeAt(index)
        pending += PendingDelete(page.item, page.originalIndex)
        emit()
        return true
    }

    fun undoLastPending(): PendingDelete? {
        val entry = pending.removeLastOrNull() ?: return null
        insertPhoto(entry)
        emit()
        return entry
    }

    fun setBatchBusy(value: Boolean) {
        if (busy == value) return
        busy = value
        emit()
    }

    fun markBatchDeleted() {
        if (pending.isEmpty()) return
        pending.forEach { app.browseStore.onTrashed(false) }
        deleted += pending
        pending.clear()
        busy = false
        emit()
    }

    fun undoBatchDeleted() {
        if (deleted.isEmpty()) return
        deleted.forEach {
            insertPhoto(it)
            app.browseStore.onRestored(false)
        }
        deleted.clear()
        busy = false
        emit()
    }

    fun pendingEntries(): List<PendingDelete> = pending.toList()

    fun deletedEntries(): List<PendingDelete> = deleted.toList()

    fun photoPageIndexOf(originalIndex: Int): Int =
        photoPages.indexOfFirst { it.originalIndex == originalIndex }

    private fun insertPhoto(entry: PendingDelete) {
        val page = FocusPage.Photo(entry.item, entry.originalIndex)
        val insertAt = photoPages.indexOfFirst { it.originalIndex > entry.originalIndex }
        if (insertAt == -1) {
            photoPages += page
        } else {
            photoPages.add(insertAt, page)
        }
    }

    private fun emit() {
        _pages.value = photoPages.toList() + FocusPage.Summary(
            pending = pending.toList(),
            deleted = deleted.toList(),
            busy = busy,
            groupSize = groupSize
        )
        currentIndex = currentIndex.coerceIn(0, (_pages.value.size - 1).coerceAtLeast(0))
    }
}
