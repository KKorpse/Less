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
    /** 无法写入系统媒体库、只存在于 Less 的收藏，校准镜像时不能把它们删掉。 */
    private val localOnlyFavorites: MutableSet<String> =
        (prefs.getStringSet(KEY_LOCAL_ONLY_FAVORITES, emptySet()) ?: emptySet()).toMutableSet()

    init {
        // 2.2.3 之前的收藏只存在本地，升级后给它们打上“仅本地”标记，
        // 免得第一次用 MediaStore 校准镜像时被清掉。
        if (!prefs.getBoolean(KEY_FAVORITES_MIGRATED, false)) {
            if (favorites.isNotEmpty()) {
                localOnlyFavorites.addAll(favorites)
            }
            prefs.edit()
                .putBoolean(KEY_FAVORITES_MIGRATED, true)
                .putStringSet(KEY_LOCAL_ONLY_FAVORITES, localOnlyFavorites.toSet())
                .apply()
        }
    }

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

    /** 设置收藏状态；只在状态真的变化时写盘。 */
    fun setFavorite(uri: Uri, favorite: Boolean) {
        val key = uri.toString()
        val changed = if (favorite) favorites.add(key) else favorites.remove(key)
        if (changed) {
            prefs.edit().putStringSet(KEY_FAVORITES, favorites.toSet()).apply()
        }
        if (!favorite) markLocalOnly(uri, false)
    }

    /** 记录这条收藏没有写进系统媒体库，校准镜像时要保留。 */
    fun markLocalOnly(uri: Uri, localOnly: Boolean) {
        val key = uri.toString()
        val changed = if (localOnly) localOnlyFavorites.add(key) else localOnlyFavorites.remove(key)
        if (changed) {
            prefs.edit()
                .putStringSet(KEY_LOCAL_ONLY_FAVORITES, localOnlyFavorites.toSet())
                .apply()
        }
    }

    fun toggleFavorite(uri: Uri): Boolean {
        val favorite = !isFavorite(uri)
        setFavorite(uri, favorite)
        return favorite
    }

    /**
     * 用 MediaStore 的 IS_FAVORITE 校准本地镜像：系统相册里的收藏/取消收藏
     * 也要能在 Less 里看到。
     */
    fun syncFavorites(values: Map<String, Boolean>) {
        if (values.isEmpty()) return
        var favoritesChanged = false
        var localOnlyChanged = false
        values.forEach { (key, favorite) ->
            if (favorite) {
                // 系统里已收藏：本机镜像跟上，并清掉“仅本地”标记。
                favoritesChanged = favorites.add(key) || favoritesChanged
                if (localOnlyFavorites.remove(key)) localOnlyChanged = true
            } else if (!localOnlyFavorites.contains(key)) {
                // 系统里没有收藏：除非这条收藏压根没写进系统，否则本机也取消。
                favoritesChanged = favorites.remove(key) || favoritesChanged
            }
        }
        if (favoritesChanged || localOnlyChanged) {
            prefs.edit()
                .putStringSet(KEY_FAVORITES, favorites.toSet())
                .putStringSet(KEY_LOCAL_ONLY_FAVORITES, localOnlyFavorites.toSet())
                .apply()
        }
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
        private const val KEY_LOCAL_ONLY_FAVORITES = "local_only_favorites"
        private const val KEY_FAVORITES_MIGRATED = "favorites_migrated"
        private const val KEY_DELETED_PHOTOS = "deleted_photos"
        private const val KEY_DELETED_VIDEOS = "deleted_videos"
        private const val KEY_CURSOR = "sequential_cursor"
    }
}
