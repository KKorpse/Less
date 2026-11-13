package com.kkorpse.less.data

import android.net.Uri

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val isVideo: Boolean,
    val dateMillis: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    /** MediaStore 中的 IS_FAVORITE 值，只有 id != 0 的媒体才有意义。 */
    val isFavorite: Boolean = false
)
