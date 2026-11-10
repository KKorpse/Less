package com.kkorpse.less.ui

import android.app.Activity
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.kkorpse.less.R
import com.kkorpse.less.data.MediaItem

/**
 * 通过系统 MediaStore 回收站删除/恢复媒体。系统会弹出确认窗口。
 */
class TrashController(
    private val activity: AppCompatActivity,
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

    private val launcher = activity.registerForActivityResult(
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
            Toast.makeText(activity, R.string.trash_unsupported, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val pendingIntent = MediaStore.createTrashRequest(
                activity.contentResolver,
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
            val message = if (trash) R.string.trash_failed else R.string.restore_failed
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }
}
