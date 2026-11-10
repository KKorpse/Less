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

class BatchTrashController(
    private val activity: AppCompatActivity,
    private val onResult: (trash: Boolean, success: Boolean) -> Unit
) {

    private var pendingTrash: Boolean? = null

    var isBusy: Boolean = false
        private set

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val trash = pendingTrash ?: return@registerForActivityResult
        pendingTrash = null
        isBusy = false
        onResult(trash, result.resultCode == Activity.RESULT_OK)
    }

    fun request(items: List<MediaItem>, trash: Boolean) {
        if (isBusy || items.isEmpty()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(activity, R.string.trash_unsupported, Toast.LENGTH_SHORT).show()
            onResult(trash, false)
            return
        }

        try {
            val pendingIntent = MediaStore.createTrashRequest(
                activity.contentResolver,
                items.map { it.uri },
                trash
            )
            pendingTrash = trash
            isBusy = true
            launcher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
        } catch (e: Exception) {
            pendingTrash = null
            isBusy = false
            Toast.makeText(
                activity,
                if (trash) R.string.batch_trash_failed else R.string.batch_restore_failed,
                Toast.LENGTH_SHORT
            ).show()
            onResult(trash, false)
        }
    }
}
