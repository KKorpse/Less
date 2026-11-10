package com.kkorpse.less.prefs

import android.content.Context
import android.net.Uri

/**
 * 浏览记录、收藏和删除次数。全部保存在本机 SharedPreferences 中。
 */
class BrowseStore(context: Context) {

    private val prefs = context.getSharedPreferences("less_browse", Context.MODE_PRIVATE)

    private val viewedPhotos: MutableSet<String> =
        (prefs.getStringSet(KEY_VIEWED_PHOTOS, emptySet()) ?: emptySet()).toMutableSet()
    private val viewedVideos: MutableSet<String> =
        (prefs.getStringSet(KEY_VIEWED_VIDEOS, emptySet()) ?: emptySet()).toMutableSet()
    private val favorites: MutableSet<String> =
        (prefs.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()).toMutableSet()

    private var deletedPhotos: Int = prefs.getInt(KEY_DELETED_PHOTOS, 0)
    private var deletedVideos: Int = prefs.getInt(KEY_DELETED_VIDEOS, 0)

    var sequentialCursor: Int
        get() = prefs.getInt(KEY_CURSOR, 0)
        set(value) {
            prefs.edit().putInt(KEY_CURSOR, value.coerceAtLeast(0)).apply()
        }

    val deletedPhotoCount: Int get() = deletedPhotos
    val deletedVideoCount: Int get() = deletedVideos

    fun markViewed(uri: Uri, isVideo: Boolean) {
        val set = if (isVideo) viewedVideos else viewedPhotos
        if (set.add(uri.toString())) {
            persistViewed(isVideo)
        }
    }

    fun isViewed(uri: Uri, isVideo: Boolean): Boolean =
        (if (isVideo) viewedVideos else viewedPhotos).contains(uri.toString())

    fun viewedUris(isVideo: Boolean): Set<String> =
        (if (isVideo) viewedVideos else viewedPhotos).toSet()

    fun clearViewed() {
        viewedPhotos.clear()
        viewedVideos.clear()
        prefs.edit()
            .remove(KEY_VIEWED_PHOTOS)
            .remove(KEY_VIEWED_VIDEOS)
            .putInt(KEY_CURSOR, 0)
            .apply()
    }

    fun isFavorite(uri: Uri): Boolean = favorites.contains(uri.toString())

    fun toggleFavorite(uri: Uri): Boolean {
        val key = uri.toString()
        val favorite = if (favorites.contains(key)) {
            favorites.remove(key)
            false
        } else {
            favorites.add(key)
            true
        }
        prefs.edit().putStringSet(KEY_FAVORITES, favorites.toSet()).apply()
        return favorite
    }

    fun favoriteUris(): Set<String> = favorites.toSet()

    fun onTrashed(isVideo: Boolean) {
        if (isVideo) {
            deletedVideos++
            prefs.edit().putInt(KEY_DELETED_VIDEOS, deletedVideos).apply()
        } else {
            deletedPhotos++
            prefs.edit().putInt(KEY_DELETED_PHOTOS, deletedPhotos).apply()
        }
    }

    fun onRestored(isVideo: Boolean) {
        if (isVideo) {
            deletedVideos = (deletedVideos - 1).coerceAtLeast(0)
            prefs.edit().putInt(KEY_DELETED_VIDEOS, deletedVideos).apply()
        } else {
            deletedPhotos = (deletedPhotos - 1).coerceAtLeast(0)
            prefs.edit().putInt(KEY_DELETED_PHOTOS, deletedPhotos).apply()
        }
    }

    private fun persistViewed(isVideo: Boolean) {
        val key = if (isVideo) KEY_VIEWED_VIDEOS else KEY_VIEWED_PHOTOS
        val set = if (isVideo) viewedVideos else viewedPhotos
        prefs.edit().putStringSet(key, set.toSet()).apply()
    }

    companion object {
        private const val KEY_VIEWED_PHOTOS = "viewed_photos"
        private const val KEY_VIEWED_VIDEOS = "viewed_videos"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_DELETED_PHOTOS = "deleted_photos"
        private const val KEY_DELETED_VIDEOS = "deleted_videos"
        private const val KEY_CURSOR = "sequential_cursor"
    }
}
