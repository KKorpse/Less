package com.kkorpse.less.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import com.github.chrisbanes.photoview.PhotoView

/**
 * 图片未放大时，把水平/垂直滑动交给 RecyclerView / 上滑删除；
 * 放大后交给 PhotoView 拖动查看细节。
 */
class FocusCardLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var photoView: PhotoView? = null

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        val zoomed = (photoView?.scale ?: 1f) > 1.01f
        super.requestDisallowInterceptTouchEvent(if (zoomed) disallowIntercept else false)
    }
}
