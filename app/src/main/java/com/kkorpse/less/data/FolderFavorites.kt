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
        return target
    }

    /** 删除我们自己创建的副本（自己的文件，不需要授权）。 */
    fun deleteCopy(context: Context, uri: Uri): Boolean =
        runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
}
