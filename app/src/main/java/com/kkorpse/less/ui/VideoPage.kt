package com.kkorpse.less.ui

import com.kkorpse.less.data.MediaItem

sealed class VideoPage {
    data class Video(
        val item: MediaItem,
        val originalIndex: Int
    ) : VideoPage()

    data class Summary(
        val pending: List<MediaItem>,
        val deleted: List<MediaItem>,
        val busy: Boolean,
        val groupSize: Int
    ) : VideoPage()
}
