package com.kkorpse.less.ui

import android.app.Activity
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.kkorpse.less.R
import com.kkorpse.less.data.MediaItem

/**
 * 通过系统 MediaStore 回收站删除/恢复媒体。系统会弹出确认窗口。
 */
class TrashController(
    caller: ActivityResultCaller,
    private val context: Context,
    private val onResult: (Request, Boolean) -> Unit
) {

    data class Request(
        val item: MediaItem,
        val trash: Boolean,
        val index: Int
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

    fun request(item: MediaItem, trash: Boolean, index: Int) {
        if (isBusy) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(context, R.string.trash_unsupported, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val pendingIntent = MediaStore.createTrashRequest(
                context.contentResolver,
                listOf(item.uri),
                trash
            )
            pending = Request(item, trash, index)
            isBusy = true
            launcher.launch(
                IntentSenderRequest.Builder(pendingIntent.intentSender).build()
            )
        } catch (e: Exception) {
            pending = null
            isBusy = false
            Toast.makeText(
                context,
                if (trash) R.string.trash_failed else R.string.restore_failed,
                Toast.LENGTH_SHORT
            ).show()
            onResult(Request(item, trash, index), false)
        }
    }
}
