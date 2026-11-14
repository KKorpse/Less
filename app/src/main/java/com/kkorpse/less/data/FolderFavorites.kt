package com.kkorpse.less.data

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore

/**
 * 用「移动到固定目录」实现收藏：
 * 收藏 = 把媒体移到 Pictures/Less/Favorites，取消收藏 = 移回原目录。
 *
 * 该目录会从 Less 的普通浏览里排除，只出现在收藏页。
 */
object FolderFavorites {

    /** MediaStore RELATIVE_PATH 需要以 / 结尾。 */
    const val PATH = "Pictures/Less/Favorites/"

    /** 映射丢失时的兜底目录，保证媒体还能回到图库里。 */
    const val FALLBACK_PATH = "Pictures/Less/"

    fun isInFavorites(relativePath: String?): Boolean =
        relativePath?.startsWith(PATH) == true

    /**
     * 直接尝试移动；没有写入授权时 MediaProvider 会抛 SecurityException，
     * 这里吞掉返回 false，由调用方去申请 createWriteRequest 后重试。
     */
    fun moveTo(resolver: ContentResolver, uri: Uri, targetPath: String): Boolean {
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, targetPath)
            }
            resolver.update(uri, values, null, null) > 0
        }.getOrDefault(false)
    }
}
