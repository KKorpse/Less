package com.kkorpse.less

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import com.kkorpse.less.prefs.AppPreferences
import com.kkorpse.less.prefs.BrowseStore
import java.io.File
import java.util.Date

class LessApp : Application(), SingletonImageLoader.Factory {

    lateinit var preferences: AppPreferences
        private set
    lateinit var browseStore: BrowseStore
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        Breadcrumbs.step(this, "app.onCreate")
        preferences = AppPreferences(this)
        browseStore = BrowseStore(this)
    }

    override fun newImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                // 视频缩略图：从视频中抽帧显示到网格里
                add(VideoFrameDecoder.Factory())
            }
            .build()
    }

    /**
     * 把未捕获异常写到 files/crash.txt，下次启动时在界面上展示，
     * 方便在没有 adb 的情况下定位线上崩溃。
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val version = packageManager
                    .getPackageInfo(packageName, 0).versionName
                val text = buildString {
                    appendLine("time: ${Date()}")
                    appendLine("version: $version")
                    appendLine("thread: ${thread.name}")
                    appendLine("--- breadcrumbs ---")
                    appendLine(Breadcrumbs.text())
                    appendLine("--- stack ---")
                    appendLine(android.util.Log.getStackTraceString(throwable))
                }
                Breadcrumbs.flushSync(this@LessApp)
                File(filesDir, CRASH_FILE).writeText(text)
                getExternalFilesDir("logs")?.mkdirs()
                getExternalFilesDir("logs")?.let { File(it, CRASH_FILE).writeText(text) }
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val CRASH_FILE = "crash.txt"

        fun crashFile(context: Context): File = File(context.filesDir, CRASH_FILE)
    }
}
