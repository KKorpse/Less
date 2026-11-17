package com.kkorpse.less.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * 收藏 = 把媒体**复制**一份到 Pictures/Less/Favorites。
 *
 * 复制只需要读原图 + 创建自己的文件，不需要写入授权、不会弹窗；
 * 原件在本组删除确认时一并清理，语义上等同于「移动」。
 */
object FolderFavorites {

    /** MediaStore RELATIVE_PATH 需要以 / 结尾。 */
    const val PATH = "Pictures/Less/Favorites/"

    /** 移出收藏后落到普通目录，照片变回普通照片。 */
    const val NORMAL_PATH = "Pictures/Less/"
    const val NORMAL_VIDEO_PATH = "Movies/Less/"

    fun isInFavorites(relativePath: String?): Boolean =
        relativePath?.startsWith(PATH) == true

    /** 复制到收藏目录；失败返回 null（调用方不应把这张标记为已收藏）。 */
    fun copyToFavorites(context: Context, item: MediaItem): Uri? {
        val resolver = context.contentResolver
        val collection = if (item.isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
            put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, PATH)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val target = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return null

        val copied = runCatching {
            resolver.openInputStream(item.uri)?.use { input ->
                resolver.openOutputStream(target)?.use { output ->
                    input.copyTo(output)
                }
            } != null
        }.getOrDefault(false)

        if (!copied) {
            runCatching { resolver.delete(target, null, null) }
            return null
        }

        // 副本的真实修改时间先对齐原图：入库时间改不了，但 mtime 可以，
        // 这样系统相册扫描后拿到的 DATE_MODIFIED 不是「现在」。
        applyFileTime(context, target, item.dateMillis)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                resolver.update(
                    target,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                )
            }
        }

        // 扫描结束后再写拍摄时间：没有 EXIF 的图扫描不会给 DATE_TAKEN，
        // 这里补成原图时间，相册才不会把副本排到最前面。
        applyRowTime(resolver, target, item.dateMillis)
        return target
    }

    /** 直接改副本文件的 mtime（我们自己的文件，无需授权）。 */
    private fun applyFileTime(context: Context, uri: Uri, dateMillis: Long) {
        if (dateMillis <= 0L) return
        runCatching {
            val path = context.contentResolver
                .query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            if (!path.isNullOrBlank()) {
                java.io.File(path).setLastModified(dateMillis)
            }
        }
    }

    /** 把媒体行的时间写回原图时间：DATE_TAKEN（毫秒）+ DATE_MODIFIED（秒）。 */
    private fun applyRowTime(resolver: android.content.ContentResolver, uri: Uri, dateMillis: Long) {
        if (dateMillis <= 0L) return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DATE_TAKEN, dateMillis)
            runCatching { put(MediaStore.MediaColumns.DATE_MODIFIED, dateMillis / 1000L) }
        }
        runCatching { resolver.update(uri, values, null, null) }
    }

    /**
     * 取消收藏但保留照片：把副本从收藏目录移回普通目录，并把收藏位清零。
     * 只处理我们自己创建的文件（收藏副本）。
     */
    fun moveOutOfFavorites(context: Context, item: MediaItem): Boolean {
        if (!isInFavorites(item.relativePath)) return false
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                if (item.isVideo) NORMAL_VIDEO_PATH else NORMAL_PATH
            )
            put(MediaStore.MediaColumns.IS_FAVORITE, 0)
        }
        val moved = runCatching {
            resolver.update(item.uri, values, null, null) >= 0
        }.getOrDefault(false)
        if (moved) runCatching { resolver.notifyChange(item.uri, null) }
        return moved
    }

    /** 删除我们自己创建的副本（自己的文件，不需要授权）。 */
    fun deleteCopy(context: Context, uri: Uri): Boolean =
        runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
}
