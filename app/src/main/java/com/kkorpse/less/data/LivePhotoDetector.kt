package com.kkorpse.less.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
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
    private const val KEY_LIVE = "live_entries_v2"
    private const val KEY_SCANNED = "scanned_ids_v2"
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

    private fun readHead(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(HEADER_BYTES)
            var read = 0
            while (read < buffer.size) {
                val count = input.read(buffer, read, buffer.size - read)
                if (count <= 0) break
                read += count
            }
            String(buffer, 0, read, Charsets.ISO_8859_1)
        }
    }.getOrNull()

    private fun detect(context: Context, item: MediaItem): Long {
        if (!item.mimeType.startsWith("image/")) return 0
        val head = readHead(context, item.uri) ?: return 0

        val length = parseMotionPhotoLength(head)
        if (length <= 0L) return 0
        // 关键校验：文件末尾必须真的是一段 MP4（`ftyp` box），
        // 避免"只是带了 OPPO 标记"的普通照片被当成 Live 图。
        if (!hasMp4Tail(context, item.uri, item.sizeBytes, length)) return 0
        return length
    }

    /** 读文件末尾 videoLength 字节处的 box 类型，确认是 MP4。 */
    private fun hasMp4Tail(
        context: Context,
        uri: Uri,
        fileSize: Long,
        videoLength: Long
    ): Boolean {
        if (fileSize <= videoLength || videoLength < 1024L) return false
        val offset = fileSize - videoLength
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                    channel.position(offset + 4)
                    val buffer = java.nio.ByteBuffer.allocate(4)
                    channel.read(buffer) == 4 &&
                        String(buffer.array(), Charsets.ISO_8859_1) == "ftyp"
                }
            } ?: false
        }.getOrDefault(false)
    }

    /**
     * 找出「在系统回收站里、并且是 Live 图」的那些照片。
     * 先查识别缓存；缓存里没有的（比如旧版本删掉的）再尝试读文件头判断。
     */
    suspend fun findTrashedLivePhotos(context: Context): List<Uri> =
        withContext(Dispatchers.IO) {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
                return@withContext emptyList()
            }
            val resolver = context.contentResolver
            val args = android.os.Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
            }
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.SIZE
            )
            val found = ArrayList<Uri>()
            runCatching {
                resolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    args,
                    null
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val uri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            cursor.getLong(0)
                        )
                        if (videoLengthOf(context, uri) > 0) {
                            found += uri
                            continue
                        }
                        val size = cursor.getLong(1)
                        val head = readHead(context, uri) ?: continue
                        val length = parseMotionPhotoLength(head)
                        if (length > 0 && hasMp4Tail(context, uri, size, length)) {
                            found += uri
                        }
                    }
                }
            }
            found
        }

    /**
     * 诊断：对给定的一批照片重新识别一遍（忽略缓存），返回可读报告。
     * 用于排查「某张图为什么被判成 / 没被判成 Live 图」。
     */
    suspend fun diagnose(context: Context, items: List<MediaItem>): String =
        withContext(Dispatchers.IO) {
            val live = ArrayList<String>()
            val rejected = ArrayList<String>()
            var scanned = 0
            items.forEach { item ->
                if (!item.mimeType.startsWith("image/")) return@forEach
                val head = readHead(context, item.uri) ?: return@forEach
                scanned++
                val parsed = parseMotionPhotoLength(head)
                val tail = if (parsed > 0) hasMp4Tail(context, item.uri, item.sizeBytes, parsed) else false
                val info = "${item.name} size=${item.sizeBytes} video=$parsed ftyp=$tail"
                when {
                    parsed > 0 && tail -> live += info
                    head.contains("MotionPhoto") -> rejected += info
                }
            }
            buildString {
                appendLine("live scan: $scanned / ${items.size}")
                appendLine("live=${live.size} rejected-with-marker=${rejected.size}")
                live.take(40).forEach { appendLine("LIVE $it") }
                rejected.take(40).forEach { appendLine("SKIP $it") }
            }
        }

    /** 从文件头的 XMP 里解析视频段长度；不是动态照片返回 0。 */
    internal fun parseMotionPhotoLength(head: String): Long {
        if (!head.contains("MotionPhoto")) return 0
        // XMP 里属性可能写成 `MotionPhoto="1"` 或 `MotionPhoto= "1"`，都认。
        val marked = Regex("MotionPhoto\\s*=\\s*[\"']1[\"']").containsMatchIn(head) ||
            head.contains("OpCamera:MotionPhotoOwner")
        if (!marked) return 0

        Regex("OpCamera:VideoLength\\s*=\\s*[\"'](\\d+)[\"']").find(head)?.let {
            return it.groupValues[1].toLongOrNull() ?: 0L
        }
        Regex("GCamera:MicroVideoOffset\\s*=\\s*[\"'](\\d+)[\"']").find(head)?.let {
            return it.groupValues[1].toLongOrNull() ?: 0L
        }
        // 标准格式：Container:Directory 里 MotionPhoto 那一段的长度（通常就是最大的那个）。
        return Regex("Item:Length\\s*=\\s*[\"'](\\d+)[\"']").findAll(head)
            .mapNotNull { it.groupValues[1].toLongOrNull() }
            .filter { it > 0 }
            .maxOrNull() ?: 0L
    }
}
