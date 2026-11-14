package com.kkorpse.less.data

import android.content.ContentResolver
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * 把收藏写进系统媒体库（MediaStore.MediaColumns.IS_FAVORITE），让系统相册的
 * “收藏 / 喜欢”相册能看到同一份数据。
 *
 * - Android 11（API 30）起有 [MediaStore.createFavoriteRequest]，但由系统弹窗确认；
 * - Android 16（API 36）起有 [MediaStore.markIsFavoriteStatus]，凭已有的
 *   READ_MEDIA_IMAGES / READ_MEDIA_VIDEO 可以直接写，不需要再弹窗。
 *
 * 只有真正由 MediaStore 索引的媒体（id != 0）才能写；通过 Photo Picker
 * 临时选中的内容仍然只保存在 Less 本地。
 */
object MediaFavorites {

    sealed interface Result {
        /** 已直接写入系统媒体库。 */
        data object Applied : Result

        /** 需要拉起系统确认弹窗，调用方用 [IntentSender] 启动后按结果回调。 */
        data class NeedsConfirmation(val intentSender: IntentSender) : Result

        /** 当前系统或这些 URI 不支持写入系统媒体库，只能保存在 Less 本地。 */
        data object Unsupported : Result
    }

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** 回读媒体库里的收藏位，确认这次写入真的生效。 */
    fun verify(resolver: ContentResolver, uris: List<Uri>, favorite: Boolean): Boolean {
        if (!isSupported() || uris.isEmpty()) return false
        return uris.all { uri ->
            val value = runCatching {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_FAVORITE), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getInt(0) == 1 else null
                    }
            }.getOrNull()
            value == favorite
        }
    }

    fun isMediaStoreMedia(item: MediaItem): Boolean =
        item.id != 0L && item.uri.authority == MediaStore.AUTHORITY

    fun setFavorite(
        resolver: ContentResolver,
        items: List<MediaItem>,
        favorite: Boolean
    ): Result {
        if (!isSupported()) return Result.Unsupported
        val uris = items.filter(::isMediaStoreMedia).map { it.uri }
        if (uris.isEmpty()) return Result.Unsupported

        if (Build.VERSION.SDK_INT >= 36) {
            runCatching { MediaStore.markIsFavoriteStatus(resolver, uris, favorite) }
            // 部分 ROM 的 markIsFavoriteStatus 是空实现：调用不报错但不改数据，
            // 所以这里必须回读确认，没写进去就退回系统确认框。
            if (verify(resolver, uris, favorite)) return Result.Applied
        }

        return runCatching {
            MediaStore.createFavoriteRequest(resolver, uris, favorite)
        }.map { Result.NeedsConfirmation(it.intentSender) }
            .getOrElse { Result.Unsupported }
    }
}
