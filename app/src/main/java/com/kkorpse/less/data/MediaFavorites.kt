package com.kkorpse.less.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.kkorpse.less.Diagnostics

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
        data class NeedsConfirmation(
            val intentSender: IntentSender,
            val mode: Mode
        ) : Result

        /** 当前系统或这些 URI 不支持写入系统媒体库，只能保存在 Less 本地。 */
        data object Unsupported : Result
    }

    enum class Mode {
        /** 系统的“收藏”确认框（createFavoriteRequest）。 */
        FAVORITE,

        /** 通用的写入授权框（createWriteRequest），授权后自己 update IS_FAVORITE。 */
        WRITE
    }

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * 用户已经授权写入后，直接更新 IS_FAVORITE。
     * 这是 createFavoriteRequest 不可用时的兜底路径。
     */
    fun applyDirect(
        resolver: ContentResolver,
        uris: List<Uri>,
        favorite: Boolean
    ): Boolean {
        if (!isSupported()) return false
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_FAVORITE, if (favorite) 1 else 0)
        }
        return uris.all { uri ->
            runCatching { resolver.update(uri, values, null, null) >= 0 }.getOrDefault(false)
        }
    }

    /**
     * 写入成功后通知媒体库与相册刷新：notifyChange + 旧版媒体扫描广播。
     * 收藏标志变化不需要重新扫描文件，但对部分 ROM 的相册刷新有帮助。
     */
    fun notifyChanged(context: Context, uris: List<Uri>) {
        val resolver = context.contentResolver
        uris.forEach { uri ->
            runCatching { resolver.notifyChange(uri, null) }
            runCatching {
                val path = resolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.DATA),
                    null,
                    null,
                    null
                )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                if (!path.isNullOrBlank()) {
                    MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
                    context.sendBroadcast(
                        Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri)
                    )
                }
            }
        }
    }

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
        favorite: Boolean,
        preferSystemDialog: Boolean = false
    ): Result {
        if (!isSupported()) return Result.Unsupported
        val uris = items.filter(::isMediaStoreMedia).map { it.uri }
        if (uris.isEmpty()) return Result.Unsupported

        // 排查用：强制走系统确认框，看 ColorOS 是否只认这条路径。
        if (preferSystemDialog && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val dialog = runCatching {
                MediaStore.createFavoriteRequest(resolver, uris, favorite)
            }.getOrNull()
            if (dialog != null) {
                Diagnostics.recordFavorite("prefer dialog -> createFavoriteRequest")
                return Result.NeedsConfirmation(dialog.intentSender, Mode.FAVORITE)
            }
        }

        if (Build.VERSION.SDK_INT >= 36) {
            val callOk = runCatching {
                MediaStore.markIsFavoriteStatus(resolver, uris, favorite)
            }.isSuccess
            // 部分 ROM 的 markIsFavoriteStatus 是空实现：调用不报错但不改数据，
            // 所以这里必须回读确认，没写进去就退回系统确认框。
            val verified = verify(resolver, uris, favorite)
            Diagnostics.recordFavorite(
                "markIsFavoriteStatus call=$callOk verified=$verified value=$favorite"
            )
            if (verified) return Result.Applied
        }

        val favoriteRequest = runCatching {
            MediaStore.createFavoriteRequest(resolver, uris, favorite)
        }.getOrNull()
        if (favoriteRequest != null) {
            Diagnostics.recordFavorite("createFavoriteRequest -> 系统确认框")
            return Result.NeedsConfirmation(favoriteRequest.intentSender, Mode.FAVORITE)
        }
        Diagnostics.recordFavorite("createFavoriteRequest 不可用，改用 createWriteRequest")

        // 部分 ROM 没实现 createFavoriteRequest，退回“申请写权限 + 自己更新 IS_FAVORITE”。
        return runCatching {
            MediaStore.createWriteRequest(resolver, uris)
        }.map { Result.NeedsConfirmation(it.intentSender, Mode.WRITE) }
            .getOrElse { Result.Unsupported }
    }
}
