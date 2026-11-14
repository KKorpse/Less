package com.kkorpse.less

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 启动过程的面包屑日志。
 *
 * 崩溃可能发生在 Java 层捕获不到的地方（native / 被系统杀进程），这时候
 * UncaughtExceptionHandler 不会执行。这里把每一步都写进公共 Downloads 目录，
 * 用户用文件管理器就能打开看到最后走到哪一步。
 */
object Breadcrumbs {

    private const val FILE_NAME = "less-startup.txt"
    private const val MAX_LINES = 200

    private val lines = ArrayDeque<String>()
    private val executor = Executors.newSingleThreadExecutor()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var publicUri: Uri? = null

    fun step(context: Context, message: String) {
        val line = "${timeFormat.format(Date())} $message"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        val snapshot = text()
        executor.execute {
            runCatching { writePublic(context.applicationContext, snapshot) }
            runCatching {
                File(context.applicationContext.filesDir, FILE_NAME).writeText(snapshot)
            }
        }
    }

    fun text(): String = synchronized(lines) { lines.joinToString("\n") }

    /** 崩溃时同步落盘，避免 executor 还没跑完进程就没了。 */
    fun flushSync(context: Context) {
        runCatching { writePublic(context.applicationContext, text()) }
    }

    private fun writePublic(context: Context, text: String) {
        val resolver = context.contentResolver
        val uri = publicUri ?: queryExisting(context) ?: insertNew(context) ?: return
        publicUri = uri
        resolver.openOutputStream(uri, "wt")?.use { output ->
            output.write(text.toByteArray())
        }
    }

    private fun queryExisting(context: Context): Uri? {
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(FILE_NAME),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
            }
        }
        return null
    }

    private fun insertNew(context: Context): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/Less"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        return runCatching {
            context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        }.getOrNull()
    }
}
