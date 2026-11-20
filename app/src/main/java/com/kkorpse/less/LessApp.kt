package com.kkorpse.less

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import com.kkorpse.less.data.MediaRepository
import com.kkorpse.less.prefs.AppPreferences
import com.kkorpse.less.prefs.BrowseStore
import com.kkorpse.less.util.AccessMode
import com.kkorpse.less.util.MediaAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
        pruneViewHistoryAsync()
    }

    /**
     * 启动时异步用现存媒体校准一次浏览记录：删掉已经不存在的条目。
     * 这样统计页的「查看」永远等于「现存且看过」的数量，不会超过总数。
     * 只在完全访问权限下做，部分授权（只选了部分照片）时不能据此删除记录。
     */
    private fun pruneViewHistoryAsync() {
        if (MediaAccess.mode(this) != AccessMode.FULL) return
        appScope.launch {
            runCatching {
                val items = MediaRepository(this@LessApp, browseStore)
                    .loadEverything()
                    .getOrElse { return@launch }
                // 读不到媒体（权限异常等）时不要清空浏览记录。
                if (items.isEmpty()) return@launch
                val photos = HashSet<String>(items.size)
                val videos = HashSet<String>(items.size)
                items.forEach {
                    if (it.isVideo) videos += it.uri.toString() else photos += it.uri.toString()
                }
                browseStore.pruneViewed(photos, videos)
            }
        }
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

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val CRASH_FILE = "crash.txt"

        fun crashFile(context: Context): File = File(context.filesDir, CRASH_FILE)
    }
}
