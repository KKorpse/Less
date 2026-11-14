package com.kkorpse.less.ui

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.kkorpse.less.Diagnostics
import com.kkorpse.less.LessApp
import com.kkorpse.less.data.FolderFavorites
import com.kkorpse.less.data.MediaItem
import java.util.concurrent.Executors

/**
 * 收藏 = 把媒体移动到 Pictures/Less/Favorites，取消收藏 = 移回原目录。
 *
 * MediaStore 里改别人的媒体需要写入授权：先直接 update，失败（SecurityException）
 * 再用 createWriteRequest 申请一次，授权后重试。授权通常能维持很久，
 * 因此大多数时候点爱心不会弹窗。
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

    private data class Move(
        val item: MediaItem,
        val targetPath: String,
        val originalPath: String?
    )

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var pending: Request? = null
    private var pendingMoves: List<Move> = emptyList()

    var isBusy: Boolean = false
        private set

    private val launcher = caller.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val request = pending
        val moves = pendingMoves
        pending = null
        pendingMoves = emptyList()
        isBusy = false
        if (request == null) return@registerForActivityResult

        if (result.resultCode != Activity.RESULT_OK) {
            onResult(request, false)
            return@registerForActivityResult
        }
        executor.execute {
            val failed = moves.filter {
                !FolderFavorites.moveTo(context.contentResolver, it.item.uri, it.targetPath)
            }
            handler.post { finish(request, moves, failed.isEmpty()) }
        }
    }

    fun request(item: MediaItem, favorite: Boolean): Boolean = request(listOf(item), favorite)

    /** @return false 表示这条媒体只能留在 Less 本地（例如 Photo Picker 选中的内容）。 */
    fun request(items: List<MediaItem>, favorite: Boolean): Boolean {
        val store = (context.applicationContext as? LessApp)?.browseStore
        val targets = items.filter { it.id != 0L }
        if (targets.isEmpty()) return false

        val moves = targets.map { item ->
            val target = if (favorite) {
                FolderFavorites.PATH
            } else {
                store?.favoriteOriginalPath(item.uri) ?: FolderFavorites.FALLBACK_PATH
            }
            Move(item, target, item.relativePath)
        }

        executor.execute {
            val failed = moves.filter {
                !FolderFavorites.moveTo(context.contentResolver, it.item.uri, it.targetPath)
            }
            handler.post {
                if (failed.isEmpty()) {
                    finish(Request(items, favorite), moves, true)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    requestWriteGrant(Request(items, favorite), failed)
                } else {
                    onResult(Request(items, favorite), false)
                }
            }
        }
        return true
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun requestWriteGrant(request: Request, failed: List<Move>) {
        val pendingIntent = runCatching {
            MediaStore.createWriteRequest(
                context.contentResolver,
                failed.map { it.item.uri }
            )
        }.getOrNull()
        if (pendingIntent == null) {
            onResult(request, false)
            return
        }
        Diagnostics.recordFavorite("request write grant for ${failed.size} item(s)")
        pending = request
        pendingMoves = failed
        isBusy = true
        launcher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
    }

    private fun finish(request: Request, moves: List<Move>, success: Boolean) {
        val store = (context.applicationContext as? LessApp)?.browseStore
        if (success && store != null) {
            moves.forEach { move ->
                if (request.favorite) {
                    store.rememberFavoritePath(move.item.uri, move.originalPath)
                } else {
                    store.forgetFavoritePath(move.item.uri)
                }
            }
        }
        Diagnostics.recordFavorite(
            "move favorite=${request.favorite} success=$success items=${moves.size}"
        )
        onResult(request, success)
    }
}
