package com.kkorpse.less.data

object PhotoFocusSession {
    var items: List<MediaItem> = emptyList()
    var startIndex: Int = 0
    var canTrash: Boolean = true
    var autoAppend: Boolean = true
}

object VideoFeedSession {
    var items: List<MediaItem> = emptyList()
    var startIndex: Int = 0
}
