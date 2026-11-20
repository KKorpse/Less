package com.kkorpse.less.data

import android.content.Context
import com.kkorpse.less.prefs.AppPreferences
import com.kkorpse.less.prefs.BrowseMode
import com.kkorpse.less.prefs.BrowseStore
import com.kkorpse.less.prefs.PhotoCategory

class PhotoGroupBuilder(
    private val context: Context,
    private val repository: MediaRepository,
    private val preferences: AppPreferences,
    private val store: BrowseStore
) {

    /**
     * 取当前这一组：如果上一次的组还能用（照片还在、仍属于当前相册），直接复用，
     * 不重新随机；否则按设置切一组新的并记下来。
     */
    suspend fun currentOrNewGroup(): Result<List<MediaItem>> {
        val loaded = loadSource()
        val photos = loaded.getOrElse { return Result.failure(it) }
        val selected = filterByCategory(photos)
        return Result.success(restoreGroup(selected) ?: createGroup(selected))
    }

    /** 明确进入下一组：重新切一组并覆盖记录。 */
    suspend fun advanceGroup(): Result<List<MediaItem>> {
        val loaded = loadSource()
        val photos = loaded.getOrElse { return Result.failure(it) }
        val selected = filterByCategory(photos)
        return Result.success(createGroup(selected))
    }

    private suspend fun loadSource(): Result<List<MediaItem>> =
        if (preferences.favoritesOnly) {
            repository.loadFavoritePhotos()
        } else {
            // 用户选的相册（文件夹）优先，没选就是系统相册全部照片。
            repository.loadPhotos(preferences.photoBucketId)
        }

    /**
     * 按「普通照片 / Live 图」筛选。识别结果会缓存，只有新增照片需要重新读文件头。
     */
    private suspend fun filterByCategory(photos: List<MediaItem>): List<MediaItem> {
        val live = LivePhotoDetector.scan(context, photos)
        return when (preferences.photoCategory) {
            PhotoCategory.LIVE -> photos.filter { live.containsKey(it.uri.toString()) }
            PhotoCategory.NORMAL -> photos.filterNot { live.containsKey(it.uri.toString()) }
        }
    }

    private fun restoreGroup(photos: List<MediaItem>): List<MediaItem>? {
        val saved = store.currentGroupUris(preferences.groupKey)
        if (saved.isEmpty()) return null
        val byUri = photos.associateBy { it.uri.toString() }
        val restored = saved.mapNotNull { byUri[it] }
        // 组里大部分照片被删掉/换相册了就重新切一组。
        val minSize = minOf(preferences.groupSize, 2)
        return if (restored.size >= minSize) restored else null
    }

    /** 最近一次选组是不是因为「都看过了」才空的，用于首页提示。 */
    var lastSelectionExhausted: Boolean = false
        private set

    private fun createGroup(photos: List<MediaItem>): List<MediaItem> {
        val group = selectGroup(photos, preferences.groupSize, preferences.browseMode)
        lastSelectionExhausted = group.isEmpty() && photos.isNotEmpty()
        store.setCurrentGroupUris(preferences.groupKey, group.map { it.uri.toString() })
        return group
    }

    /**
     * 选组：**看过的照片不会再被选进来**（两种模式都一样，也不区分相册）。
     * 想重新开始只能去「统计 → 重置浏览记录」。
     */
    private fun selectGroup(
        photos: List<MediaItem>,
        size: Int,
        mode: BrowseMode
    ): List<MediaItem> {
        if (photos.isEmpty() || size <= 0) return emptyList()

        return when (mode) {
            BrowseMode.RANDOM -> photos
                .filterNot { store.isViewed(it.uri, false) }
                .shuffled()
                .take(size)

            BrowseMode.SEQUENTIAL -> {
                val selected = ArrayList<MediaItem>(size)
                var index = store.sequentialCursor % photos.size
                var visited = 0
                while (visited < photos.size && selected.size < size) {
                    val item = photos[index]
                    if (!store.isViewed(item.uri, false)) selected += item
                    index = (index + 1) % photos.size
                    visited++
                }
                store.sequentialCursor = index
                selected
            }
        }
    }
}
