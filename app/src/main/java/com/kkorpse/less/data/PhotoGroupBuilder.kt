package com.kkorpse.less.data

import com.kkorpse.less.prefs.AppPreferences
import com.kkorpse.less.prefs.BrowseMode
import com.kkorpse.less.prefs.BrowseStore

class PhotoGroupBuilder(
    private val repository: MediaRepository,
    private val preferences: AppPreferences,
    private val store: BrowseStore
) {

    suspend fun newGroup(): Result<List<MediaItem>> {
        // 用户选的相册（文件夹）优先，没选就是系统相册全部照片。
        return repository.loadPhotos(preferences.photoBucketId).map { photos ->
            selectGroup(photos, preferences.groupSize, preferences.browseMode)
        }
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
