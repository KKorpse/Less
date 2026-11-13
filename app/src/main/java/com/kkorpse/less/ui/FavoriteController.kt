package com.kkorpse.less.ui

import android.app.Activity
import android.content.Context
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.kkorpse.less.data.MediaFavorites
import com.kkorpse.less.data.MediaItem

/**
 * 把收藏同步到系统媒体库。
 *
 * Android 16 起直接调用 MediaStore.markIsFavoriteStatus，不弹窗；
 * Android 11–15 走 MediaStore.createFavoriteRequest，由系统弹一次确认框。
 * 更老的系统或 Photo Picker 选中的内容只能留在 Less 本地。
 *
 * 只有需要系统确认或写入成功时才会回调；不支持的情况不回调，按“仅本地”处理。
 */
class FavoriteController(
    caller: ActivityResultCaller,
    private val context: Context,
    private val onResult: (Request, Boolean) -> Unit
) {

    data class Request(
        val items: List<MediaItem>,
        val favorite: Boolean
    )

    private var pending: Request? = null
    var isBusy: Boolean = false
        private set

    private val launcher = caller.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val request = pending
        pending = null
        isBusy = false
        if (request != null) {
            onResult(request, result.resultCode == Activity.RESULT_OK)
        }
    }

    fun request(item: MediaItem, favorite: Boolean): Boolean = request(listOf(item), favorite)

    /** @return false 表示这次收藏只能留在 Less 本地。 */
    fun request(items: List<MediaItem>, favorite: Boolean): Boolean {
        if (items.isEmpty()) return false
        if (isBusy) return true
        return when (val result = MediaFavorites.setFavorite(context.contentResolver, items, favorite)) {
            is MediaFavorites.Result.Applied -> {
                onResult(Request(items, favorite), true)
                true
            }

            is MediaFavorites.Result.NeedsConfirmation -> {
                pending = Request(items, favorite)
                isBusy = true
                launcher.launch(IntentSenderRequest.Builder(result.intentSender).build())
                true
            }

            MediaFavorites.Result.Unsupported -> false
        }
    }
}
