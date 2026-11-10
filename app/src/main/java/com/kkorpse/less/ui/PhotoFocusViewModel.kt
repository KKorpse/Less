package com.kkorpse.less.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.kkorpse.less.LessApp
import com.kkorpse.less.data.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class PhotoFocusViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as LessApp

    private val photoPages = mutableListOf<FocusPage.Photo>()
    private val pending = mutableListOf<PendingDelete>()
    private val deleted = mutableListOf<PendingDelete>()
    private var busy = false

    private val _pages = MutableStateFlow<List<FocusPage>>(emptyList())
    val pages = _pages.asStateFlow()

    var groupSize: Int = 0
        private set

    var currentIndex: Int = 0

    private var initialized = false

    fun initialize(items: List<MediaItem>, startIndex: Int) {
        if (initialized) return
        initialized = true
        groupSize = items.size
        items.forEachIndexed { index, item ->
            photoPages += FocusPage.Photo(item, index)
        }
        currentIndex = startIndex.coerceIn(0, items.lastIndex.coerceAtLeast(0))
        emit()
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
