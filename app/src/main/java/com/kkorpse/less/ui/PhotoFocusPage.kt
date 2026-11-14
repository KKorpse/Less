package com.kkorpse.less.ui

import com.kkorpse.less.data.MediaItem

sealed class FocusPage {
    data class Photo(
        val item: MediaItem,
        val originalIndex: Int
    ) : FocusPage()

    data class Summary(
        val pending: List<PendingDelete>,
        val busy: Boolean,
        val groupSize: Int
    ) : FocusPage()
}

data class PendingDelete(
    val item: MediaItem,
    val originalIndex: Int,
    /** 汇总页默认全部勾选，点一下取消。 */
    val selected: Boolean = true
)
