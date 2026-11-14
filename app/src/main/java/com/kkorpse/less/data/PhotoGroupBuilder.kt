package com.kkorpse.less.data

import com.kkorpse.less.prefs.AppPreferences
import com.kkorpse.less.prefs.BrowseMode
import com.kkorpse.less.prefs.BrowseStore

class PhotoGroupBuilder(
    private val repository: MediaRepository,
    private val preferences: AppPreferences,
    private val store: BrowseStore
) {

    /**
     * 取当前这一组：如果上一次的组还能用（照片还在、仍属于当前相册），直接复用，
     * 不重新随机；否则按设置切一组新的并记下来。
     */
    suspend fun currentOrNewGroup(): Result<List<MediaItem>> {
        // 用户选的相册（文件夹）优先，没选就是系统相册全部照片。
        return repository.loadPhotos(preferences.photoBucketId).map { photos ->
            restoreGroup(photos) ?: createGroup(photos)
        }
    }

    /** 明确进入下一组：重新切一组并覆盖记录。 */
    suspend fun advanceGroup(): Result<List<MediaItem>> {
        return repository.loadPhotos(preferences.photoBucketId).map { photos ->
            createGroup(photos)
        }
    }

    private fun restoreGroup(photos: List<MediaItem>): List<MediaItem>? {
        val saved = store.currentGroupUris()
        if (saved.isEmpty()) return null
        val byUri = photos.associateBy { it.uri.toString() }
        val restored = saved.mapNotNull { byUri[it] }
        // 组里大部分照片被删掉/换相册了就重新切一组。
        val minSize = minOf(preferences.groupSize, 2)
        return if (restored.size >= minSize) restored else null
    }

    private fun createGroup(photos: List<MediaItem>): List<MediaItem> {
        val group = selectGroup(photos, preferences.groupSize, preferences.browseMode)
        store.setCurrentGroupUris(group.map { it.uri.toString() })
        return group
    }

    private fun selectGroup(
        photos: List<MediaItem>,
        size: Int,
        mode: BrowseMode
    ): List<MediaItem> {
        if (photos.isEmpty() || size <= 0) return emptyList()

        return when (mode) {
            BrowseMode.RANDOM -> photos.shuffled().take(size)

            BrowseMode.SEQUENTIAL -> {
                val start = store.sequentialCursor % photos.size
                val selected = ArrayList<MediaItem>(size)
                var index = start
                var visited = 0
                while (visited < photos.size && selected.size < size) {
                    selected += photos[index]
                    index = (index + 1) % photos.size
                    visited++
                }
                store.sequentialCursor = index
                selected
            }
        }
    }
}
