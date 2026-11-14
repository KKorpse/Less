package com.kkorpse.less

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.kkorpse.less.util.MediaAccess

/**
 * 本地诊断信息：设备/系统/权限、启动面包屑、最近的图片加载错误。
 * 设置页可以一键复制，方便反馈「黑图 / 打不开」这类没有崩溃堆栈的问题。
 */
object Diagnostics {

    private const val MAX_ERRORS = 40
    private val errors = ArrayDeque<String>()

    fun recordError(uri: String, throwable: Throwable?) {
        val line = buildString {
            append(System.currentTimeMillis())
            append(' ')
            append(uri)
            append(' ')
            append(throwable?.javaClass?.simpleName ?: "error")
            append(": ")
            append(throwable?.message ?: "")
        }
        synchronized(errors) {
            errors.addLast(line)
            while (errors.size > MAX_ERRORS) errors.removeFirst()
        }
    }

    fun report(context: Context): String {
        val app = context.applicationContext as? LessApp
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.versionCode})"
        }.getOrElse { "unknown" }

        return buildString {
            appendLine("app: $version")
            appendLine(
                "device: ${Build.MANUFACTURER} ${Build.MODEL} / Android " +
                    "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
            )
            appendLine("media access: ${MediaAccess.mode(context)}")
            appendLine("photo album: ${app?.preferences?.photoBucketName ?: "系统相册"}")
            appendLine("photo group: ${app?.preferences?.groupSize ?: 0} / video group: ${app?.preferences?.videoGroupSize ?: 0}")
            appendLine("--- startup breadcrumbs ---")
            appendLine(Breadcrumbs.text())
            appendLine("--- image load errors (${synchronized(errors) { errors.size }}) ---")
            synchronized(errors) { errors.forEach { appendLine(it) } }
        }.trim()
    }
}
