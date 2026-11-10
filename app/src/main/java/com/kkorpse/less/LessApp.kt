package com.kkorpse.less

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import com.kkorpse.less.prefs.AppPreferences
import com.kkorpse.less.prefs.BrowseStore

class LessApp : Application(), SingletonImageLoader.Factory {

    lateinit var preferences: AppPreferences
        private set
    lateinit var browseStore: BrowseStore
        private set

    override fun onCreate() {
        super.onCreate()
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
}
