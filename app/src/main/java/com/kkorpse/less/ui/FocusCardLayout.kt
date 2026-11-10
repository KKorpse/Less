package com.kkorpse.less.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.util.AttributeSet
import android.widget.FrameLayout
import com.github.chrisbanes.photoview.PhotoView

/**
 * 未放大时把滑动交给 RecyclerView / 上滑删除；放大后交给 PhotoView 拖动。
 * 同时把卡片内容裁剪到 24dp 圆角，保证照片四角是圆的。
 */
class FocusCardLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var photoView: PhotoView? = null

    private val clipPath = Path()
    private val cornerRadius = 24f * resources.displayMetrics.density

    override fun dispatchDraw(canvas: Canvas) {
        clipPath.reset()
        clipPath.addRoundRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            cornerRadius,
            cornerRadius,
            Path.Direction.CW
        )
        val save = canvas.save()
        canvas.clipPath(clipPath)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        val zoomed = (photoView?.scale ?: 1f) > 1.01f
        super.requestDisallowInterceptTouchEvent(if (zoomed) disallowIntercept else false)
    }
}
