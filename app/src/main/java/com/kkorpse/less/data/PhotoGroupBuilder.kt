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
        return repository.loadPhotos().map { photos ->
            selectGroup(
                photos = photos,
                size = preferences.groupSize,
                mode = preferences.browseMode,
                excludeUris = emptySet()
            )
        }
    }

    suspend fun appendGroup(existing: List<MediaItem>): Result<List<MediaItem>> {
        return repository.loadPhotos().map { photos ->
            selectGroup(
                photos = photos,
                size = preferences.groupSize,
                mode = preferences.browseMode,
                excludeUris = existing.map { it.uri.toString() }.toSet()
            )
        }
    }

    private fun selectGroup(
        photos: List<MediaItem>,
        size: Int,
        mode: BrowseMode,
        excludeUris: Set<String>
    ): List<MediaItem> {
        if (photos.isEmpty() || size <= 0) return emptyList()

        val pool = photos.filter { it.uri.toString() !in excludeUris }
        if (pool.isEmpty()) return emptyList()

        return when (mode) {
            BrowseMode.RANDOM -> pool.shuffled().take(size)

            BrowseMode.SEQUENTIAL -> {
                val start = store.sequentialCursor % photos.size
                val selected = ArrayList<MediaItem>(size)
                var index = start
                var visited = 0
                while (visited < photos.size && selected.size < size) {
                    val item = photos[index]
                    if (item.uri.toString() !in excludeUris) {
                        selected += item
                    }
                    index = (index + 1) % photos.size
                    visited++
                }
                store.sequentialCursor = index
                selected
            }
        }
    }
}
