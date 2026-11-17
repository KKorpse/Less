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
    private val repository = MediaRepository(application, app.browseStore)
    private val groupBuilder = PhotoGroupBuilder(
        repository,
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

    private var expanding = false

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

    /**
     * 展开：把当前这张在完整相册里相邻的左右各「展开宽度」张补进本组，放在它后面。
     * 已经在组里或已经标记待删的会跳过；回调拿到实际补进来的张数（0 表示附近没有可加的）。
     */
    fun expand(page: FocusPage.Photo, onDone: (Int) -> Unit) {
        if (expanding) return
        // 收藏模式只浏览收藏，展开会拉进普通照片，直接不生效。
        if (app.preferences.favoritesOnly) {
            onDone(0)
            return
        }
        if (photoPages.none { it.item.uri == page.item.uri }) return
        expanding = true
        viewModelScope.launch {
            val photos = repository.loadPhotos(app.preferences.photoBucketId)
                .getOrElse { emptyList() }
            expanding = false

            val anchor = photoPages.indexOfFirst { it.item.uri == page.item.uri }
            val center = photos.indexOfFirst { it.uri == page.item.uri }
            if (anchor < 0 || center < 0) {
                onDone(0)
                return@launch
            }

            val known = HashSet<String>(photoPages.size + pending.size)
            photoPages.mapTo(known) { it.item.uri.toString() }
            pending.mapTo(known) { it.item.uri.toString() }

            val radius = app.preferences.expandRadius
            val neighbors = ((center - radius)..(center + radius))
                .filter { it != center && it in photos.indices }
                .map { photos[it] }
                .filter { it.uri.toString() !in known }
                .distinctBy { it.uri.toString() }
            if (neighbors.isEmpty()) {
                onDone(0)
                return@launch
            }

            // 序号只用于「删除后撤回」找回相对位置，取一段不会与现有冲突的值即可。
            var nextIndex = (photoPages.maxOfOrNull { it.originalIndex } ?: -1) + 1
            val added = neighbors.map { FocusPage.Photo(it, nextIndex++) }
            photoPages.addAll(anchor + 1, added)
            groupSize += added.size
            persistCurrentGroup()
            emit()
            onDone(added.size)
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

    /** 收藏模式：移出收藏后清掉这一组的待处理项，不计入删除数。 */
    fun completeUnfavorite(items: List<MediaItem>) {
        val uris = items.map { it.uri }.toSet()
        if (uris.isEmpty()) return
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
        val mode = app.preferences.groupKey
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

    /** 展开后这一组变大了，同步写回，退出重进仍是展开后的组。 */
    private fun persistCurrentGroup() {
        if (!autoNextGroup) return
        val mode = app.preferences.groupKey
        app.browseStore.setCurrentGroupUris(mode, photoPages.map { it.item.uri.toString() })
    }

    private fun persistPendingMarks() {
        if (!autoNextGroup) return
        val mode = app.preferences.groupKey
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
