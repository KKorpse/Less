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
        MediaRepository(application, app.browseStore),
        app.preferences,
        app.browseStore
    )

    private val photoPages = mutableListOf<FocusPage.Photo>()
    private val pending = mutableListOf<PendingDelete>()
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
        restorePendingMarks()
        currentIndex = startIndex.coerceIn(0, photoPages.lastIndex.coerceAtLeast(0))
        emit()
    }

    fun loadNextGroup() {
        if (!autoNextGroup || loadingNext) return
        loadingNext = true
        busy = true
        emit()
        viewModelScope.launch {
            groupBuilder.advanceGroup()
                .onSuccess { photos ->
                    if (photos.isEmpty()) {
                        loadingNext = false
                        busy = false
                        emit()
                        return@onSuccess
                    }
                    photoPages.clear()
                    pending.clear()
                    persistPendingMarks()
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
        val index = photoPages.indexOfFirst { it.item.uri == page.item.uri }
        if (index == -1) return false
        photoPages.removeAt(index)
        pending += PendingDelete(page.item, page.originalIndex)
        persistPendingMarks()
        emit()
        return true
    }

    fun undoLastPending(): PendingDelete? {
        val entry = pending.removeLastOrNull() ?: return null
        insertPhoto(entry)
        persistPendingMarks()
        emit()
        return entry
    }

    fun setBatchBusy(value: Boolean) {
        if (busy == value) return
        busy = value
        emit()
    }

    /** 汇总页里点一张图：切换是否勾选。 */
    fun togglePendingSelection(item: MediaItem) {
        val index = pending.indexOfFirst { it.item.uri == item.uri }
        if (index < 0) return
        pending[index] = pending[index].copy(selected = !pending[index].selected)
        emit()
    }

    /** 汇总页「确认删除」：勾选的移入回收站，然后直接进入下一组。 */
    fun completeBatchDelete(items: List<MediaItem>) {
        val uris = items.map { it.uri }.toSet()
        if (uris.isEmpty()) return
        pending.filter { it.item.uri in uris }.forEach {
            app.browseStore.onTrashed(false)
        }
        pending.removeAll { it.item.uri in uris }
        pending.clear()
        persistPendingMarks()
        busy = false
        currentIndex = 0
        emit()
    }

    /** 汇总页「放弃，再来一组」：丢掉这一组的删除标记。 */
    fun discardGroup() {
        pending.clear()
        persistPendingMarks()
        busy = false
        currentIndex = 0
        emit()
    }

    /** 退出前标记的删除项，重新进入时恢复。 */
    private fun restorePendingMarks() {
        if (!autoNextGroup) return
        val mode = app.preferences.browseMode.name
        val saved = app.browseStore.pendingMarks(mode)
        if (saved.isEmpty()) return
        val byUri = photoPages.associateBy { it.item.uri.toString() }
        saved.forEach { mark ->
            val parts = mark.split('|')
            if (parts.size != 2) return@forEach
            val page = byUri[parts[0]] ?: return@forEach
            val originalIndex = parts[1].toIntOrNull() ?: page.originalIndex
            pending += PendingDelete(page.item, originalIndex)
            photoPages.remove(page)
        }
    }

    private fun persistPendingMarks() {
        if (!autoNextGroup) return
        val mode = app.preferences.browseMode.name
        app.browseStore.setPendingMarks(
            mode,
            pending.map { "${it.item.uri}|${it.originalIndex}" }
        )
    }

    fun pendingEntries(): List<PendingDelete> = pending.toList()

    fun selectedEntries(): List<PendingDelete> = pending.filter { it.selected }

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
            busy = busy,
            groupSize = groupSize
        )
        currentIndex = currentIndex.coerceIn(0, (_pages.value.size - 1).coerceAtLeast(0))
    }
}
