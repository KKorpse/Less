package com.kkorpse.less.data

/**
 * 进程内的浏览会话。ViewerActivity 从这里拿到当前网格（或系统选择器）里的媒体列表，
 * 避免通过 Intent 传递可能很大的 Parcelable 列表。
 */
object MediaSession {
    @Volatile
    var items: List<MediaItem> = emptyList()
}
