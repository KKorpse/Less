package com.kkorpse.less.data

import android.content.Context
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource

/**
 * 让播放器把「JPEG 末尾那段 MP4」当成一个独立的视频文件来读。
 *
 * 播放器拿到的虚拟文件长度是 [videoLength]，位置从 0 开始；
 * 这里把每次读取换算回真实文件的 `偏移 + 位置`，所以不用把视频导出成临时文件。
 */
object LiveVideoSource {

    fun factory(context: Context, fileSize: Long, videoLength: Long): DataSource.Factory {
        val upstream = DefaultDataSource.Factory(context)
        return ResolvingDataSource.Factory(upstream) { spec ->
            val position = spec.position.coerceAtLeast(0L)
            if (videoLength <= 0L || position >= videoLength) {
                spec
            } else {
                val remaining = videoLength - position
                val requested = spec.length
                spec.buildUpon()
                    .setPosition(fileSize - videoLength + position)
                    .setLength(
                        if (requested == C.LENGTH_UNSET.toLong()) remaining
                        else minOf(requested, remaining)
                    )
                    .build()
            }
        }
    }
}
