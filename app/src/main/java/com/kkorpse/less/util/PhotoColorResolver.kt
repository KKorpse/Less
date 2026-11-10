package com.kkorpse.less.util

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.util.Size
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 从当前照片提取整体色调，用来设置浏览页的整页背景色。
 */
class PhotoColorResolver {

    private val cache = mutableMapOf<String, Int>()

    suspend fun resolve(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val key = uri.toString()
        cache[key]?.let { return@withContext it }

        val bitmap = runCatching {
            context.contentResolver.loadThumbnail(uri, Size(64, 64), null)
        }.getOrNull()

        val color = if (bitmap != null) {
            val palette = runCatching { Palette.from(bitmap).generate() }.getOrNull()
            bitmap.recycle()
            palette?.getDominantColor(FALLBACK_COLOR) ?: FALLBACK_COLOR
        } else {
            FALLBACK_COLOR
        }
        cache[key] = color
        color
    }

    fun pageColor(color: Int): Int =
        ColorUtils.blendARGB(color, Color.BLACK, 0.78f)

    fun topColor(color: Int): Int =
        ColorUtils.blendARGB(color, Color.BLACK, 0.68f)

    companion object {
        const val FALLBACK_COLOR = 0xFF16161A.toInt()
    }
}
