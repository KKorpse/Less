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
    val pages: List<VideoPage> = emptyList(),
    val error: String? = null
)

/**
 * 视频也按「组」浏览：组内看完后在组尾汇总页统一确认删除，
 * 不再每点一次删除就弹一次系统确认框。
 */
class VideoFeedViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as LessApp
    private val repository = MediaRepository(application, app.browseStore)

    private val _state = MutableStateFlow(VideoFeedState())
    val state = _state.asStateFlow()

    private val videos = mutableListOf<MediaItem>()
    private val pending = mutableListOf<MediaItem>()
    private val deleted = mutableListOf<MediaItem>()

    private var busy = false
    private var autoNext = true
    private var groupSize = 0
    private var loadingNext = false
    private var initialized = false

    var currentIndex: Int = 0
        private set

    fun initialize(sessionItems: List<MediaItem>, startIndex: Int) {
        if (initialized) return
        initialized = true
        if (sessionItems.isNotEmpty()) {
            // 从收藏页进入：只浏览这一批，不自动换组。
            autoNext = false
            videos.addAll(sessionItems)
            groupSize = sessionItems.size
            currentIndex = startIndex.coerceIn(0, sessionItems.lastIndex)
            emit()
        } else {
            loadGroup()
        }
    }

    private fun loadGroup() {
        if (loadingNext) return
        loadingNext = true
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            repository.loadVideos()
                .onSuccess { all ->
                    videos.clear()
                    pending.clear()
                    deleted.clear()
                    val size = app.preferences.videoGroupSize.coerceAtLeast(1)
                    if (all.isEmpty()) {
                        groupSize = 0
                    } else {
                        val start = app.browseStore.videoCursor % all.size
                        val group = ArrayList<MediaItem>(size)
                        var index = start
                        var visited = 0
                        while (visited < all.size && group.size < size) {
                            group += all[index]
                            index = (index + 1) % all.size
                            visited++
                        }
                        app.browseStore.videoCursor = index
                        videos.addAll(group)
                        groupSize = group.size
                    }
                    currentIndex = 0
                    loadingNext = false
                    emit()
                }
                .onFailure {
                    loadingNext = false
                    autoNext = false
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

    /** 标记删除：先从当前组拿掉，等组尾一起确认。 */
    fun markForDeletion(item: MediaItem): Boolean {
        val index = videos.indexOfFirst { it.uri == item.uri }
        if (index < 0) return false
        videos.removeAt(index)
        pending += item
        currentIndex = currentIndex.coerceIn(0, (videos.size - 1).coerceAtLeast(0))
        emit()
        return true
    }

    fun undoLastMark(): MediaItem? {
        val item = pending.removeLastOrNull() ?: return null
        videos.add(item)
        emit()
        return item
    }

    fun hasPendingMarks(): Boolean = pending.isNotEmpty()

    fun pendingItems(): List<MediaItem> = pending.toList()

    fun deletedItems(): List<MediaItem> = deleted.toList()

    fun canUndoBatch(): Boolean = deleted.isNotEmpty()

    fun setBatchBusy(value: Boolean) {
        if (busy == value) return
        busy = value
        emit()
    }

    fun markBatchDeleted() {
        if (pending.isEmpty()) return
        pending.forEach { app.browseStore.onTrashed(true) }
        deleted += pending
        pending.clear()
        busy = false
        currentIndex = 0
        emit()
    }

    fun undoBatchDeleted() {
        if (deleted.isEmpty()) return
        deleted.forEach {
            videos.add(it)
            app.browseStore.onRestored(true)
        }
        deleted.clear()
        busy = false
        emit()
    }

    /** 组尾汇总页的「下一组」。 */
    fun nextGroup() {
        if (!autoNext) return
        loadGroup()
    }

    fun hasNextGroup(): Boolean = autoNext

    private fun emit() {
        val pages = ArrayList<VideoPage>(videos.size + 1)
        videos.forEachIndexed { index, item -> pages += VideoPage.Video(item, index) }
        pages += VideoPage.Summary(
            pending = pending.toList(),
            deleted = deleted.toList(),
            busy = busy,
            groupSize = groupSize
        )
        _state.value = VideoFeedState(loading = false, pages = pages)
    }
}
