package com.kkorpse.less.ui

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.kkorpse.less.Diagnostics
import com.kkorpse.less.LessApp
import com.kkorpse.less.data.FolderFavorites
import com.kkorpse.less.data.MediaItem
import java.util.concurrent.Executors

/**
 * 收藏 = 把媒体复制一份到 Pictures/Less/Favorites（不需要写入授权、不弹窗）。
 * 原图会在本组「确认删除」时一起送进系统回收站，语义上等同于移动。
 * 取消收藏 = 删除我们创建的那份副本，不动原图。
 */
class FavoriteController(
    private val context: Context,
    private val onResult: (Request, Boolean) -> Unit
) {

    data class Request(
        val items: List<MediaItem>,
        val favorite: Boolean
    )

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private val store get() = (context.applicationContext as? LessApp)?.browseStore

    fun request(item: MediaItem, favorite: Boolean): Boolean = request(listOf(item), favorite)

    fun request(items: List<MediaItem>, favorite: Boolean): Boolean {
        val targets = items.filter { it.id != 0L }
        if (targets.isEmpty()) return false

        targets.forEach { item ->
            if (favorite) {
                executor.execute { copyOne(item) }
            } else {
                executor.execute { removeOne(item) }
            }
        }
        return true
    }

    private fun copyOne(item: MediaItem) {
        val copy = FolderFavorites.copyToFavorites(context, item)
        handler.post {
            if (copy == null) {
                Diagnostics.recordFavorite("copy failed ${item.name}")
                onResult(Request(listOf(item), true), false)
                return@post
            }
            store?.rememberFavoriteCopy(item.uri, copy)
            store?.setFavorite(copy, true)
            // 原图先留着，组尾确认删除时一起清理。
            store?.addPendingCleanup(item.uri)
            Diagnostics.recordFavorite("copied ${item.name} -> $copy")
            onResult(Request(listOf(item), true), true)
        }
    }

    private fun removeOne(item: MediaItem) {
        val isCopy = FolderFavorites.isInFavorites(item.relativePath)
        val original = if (isCopy) store?.originalOfCopy(item.uri) else item.uri
        val copy = store?.favoriteCopyOf(item.uri) ?: original?.let { store?.favoriteCopyOf(it) }
        val deleted = copy?.let { FolderFavorites.deleteCopy(context, it) } ?: true
        handler.post {
            if (original != null) {
                store?.forgetFavoriteCopy(original)
                store?.removePendingCleanup(listOf(original))
                store?.setFavorite(original, false)
            }
            store?.setFavorite(item.uri, false)
            Diagnostics.recordFavorite("unfavorite ${item.name} deleted=$deleted")
            onResult(Request(listOf(item), false), true)
        }
    }
}
