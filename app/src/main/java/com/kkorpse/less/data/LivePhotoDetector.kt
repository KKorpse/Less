package com.kkorpse.less.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 识别「动态照片 / Live 图」。
 *
 * OPPO / ColorOS 和 Google 一样，是把一整段 MP4 直接追加在 JPEG 末尾，
 * 并在 JPEG 的 XMP 里标记：
 * - `Camera:MotionPhoto="1"`（Google 标准）
 * - `Container:Directory` 里 `Item:Semantic="MotionPhoto"` + `Item:Length`
 * - OPPO 扩展：`OpCamera:MotionPhotoOwner="oplus"`、`OpCamera:VideoLength`
 *
 * 这里只读文件开头的 XMP（16KB 足够），把「哪张是 Live 图 + 视频段长度」缓存下来，
 * 后面播放时用 `文件大小 - 视频长度` 就能定位到内嵌的那段 MP4。
 */
object LivePhotoDetector {

    private const val PREFS = "less_live_photo"
    private const val KEY_LIVE = "live_entries"
    private const val KEY_SCANNED = "scanned_ids"
    private const val HEADER_BYTES = 16 * 1024

    private var liveCache: MutableMap<String, Long>? = null
    private var scannedCache: MutableSet<String>? = null

    /** 已经识别出来的 Live 图视频段长度；不是 Live 图返回 0。 */
    fun videoLengthOf(context: Context, uri: Uri): Long =
        live(context)[uri.toString()] ?: 0L

    fun isLivePhoto(context: Context, uri: Uri): Boolean = videoLengthOf(context, uri) > 0

    /**
     * 扫描还没看过的照片，返回「uri → 视频段长度」的完整结果。
     * 结果会持久化，第二次开始只扫新增的照片。
     */
    suspend fun scan(context: Context, items: List<MediaItem>): Map<String, Long> =
        withContext(Dispatchers.IO) {
            val live = live(context)
            val scanned = scanned(context)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var changed = false
            items.forEach { item ->
                val id = item.id.toString()
                if (scanned.contains(id)) return@forEach
                val length = detect(context, item)
                scanned += id
                if (length > 0) live[item.uri.toString()] = length
                changed = true
            }
            if (changed) {
                prefs.edit()
                    .putStringSet(
                        KEY_LIVE,
                        live.map { "${it.key}|${it.value}" }.toSet()
                    )
                    .putStringSet(KEY_SCANNED, scanned.toSet())
                    .apply()
            }
            live.toMap()
        }

    private fun live(context: Context): MutableMap<String, Long> {
        liveCache?.let { return it }
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_LIVE, emptySet())
            .orEmpty()
        val map = HashMap<String, Long>(raw.size)
        raw.forEach { entry ->
            val index = entry.lastIndexOf('|')
            if (index <= 0) return@forEach
            val length = entry.substring(index + 1).toLongOrNull() ?: return@forEach
            map[entry.substring(0, index)] = length
        }
        liveCache = map
        return map
    }

    private fun scanned(context: Context): MutableSet<String> {
        scannedCache?.let { return it }
        val set = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_SCANNED, emptySet())
            .orEmpty()
            .toMutableSet()
        scannedCache = set
        return set
    }

    private fun detect(context: Context, item: MediaItem): Long {
        if (!item.mimeType.startsWith("image/")) return 0
        val head = runCatching {
            context.contentResolver.openInputStream(item.uri)?.use { input ->
                val buffer = ByteArray(HEADER_BYTES)
                var read = 0
                while (read < buffer.size) {
                    val count = input.read(buffer, read, buffer.size - read)
                    if (count <= 0) break
                    read += count
                }
                String(buffer, 0, read, Charsets.ISO_8859_1)
            }
        }.getOrNull() ?: return 0

        return parseMotionPhotoLength(head)
    }

    /** 从文件头的 XMP 里解析视频段长度；不是动态照片返回 0。 */
    internal fun parseMotionPhotoLength(head: String): Long {
        if (!head.contains("MotionPhoto")) return 0
        val marked = head.contains("Camera:MotionPhoto=\"1\"") ||
            head.contains("OpCamera:MotionPhotoOwner")
        if (!marked) return 0

        Regex("OpCamera:VideoLength=\"(\\d+)\"").find(head)?.let {
            return it.groupValues[1].toLongOrNull() ?: 0L
        }
        Regex("GCamera:MicroVideoOffset=\"(\\d+)\"").find(head)?.let {
            return it.groupValues[1].toLongOrNull() ?: 0L
        }
        // 标准格式：Container:Directory 里 MotionPhoto 那一段的长度（通常就是最大的那个）。
        return Regex("Item:Length=\"(\\d+)\"").findAll(head)
            .mapNotNull { it.groupValues[1].toLongOrNull() }
            .filter { it > 0 }
            .maxOrNull() ?: 0L
    }
}
