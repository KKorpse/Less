package com.kkorpse.less

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder

class LessApp : Application(), SingletonImageLoader.Factory {

    override fun newImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                // 视频缩略图：从视频中抽帧显示到网格里
                add(VideoFrameDecoder.Factory())
            }
            .build()
    }
}
