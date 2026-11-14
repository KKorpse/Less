package com.kkorpse.less.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import com.github.chrisbanes.photoview.PhotoView

/**
 * 未放大时把滑动交给 RecyclerView / 上滑删除；放大后交给 PhotoView 拖动。
 *
 * 裁剪范围始终跟随图片自身的显示区域：1x 时是圆角卡片，放大后圆角随图片一起
 * 放大、移出画面，不会留下一个固定大小的框。
 */
class FocusCardLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var photoView: PhotoView? = null

    private val clipPath = Path()
    private val cornerRadius = 16f * resources.displayMetrics.density

    /** 双指按下期间不允许 RecyclerView 抢走手势，否则捏合会被当成横向翻页。 */
    private var pinching = false

    override fun dispatchDraw(canvas: Canvas) {
        val photo = photoView
        val scale = photo?.scale ?: 1f
        val displayRect = photo?.displayRect

        clipPath.reset()
        if (displayRect != null && !displayRect.isEmpty) {
            val radius = if (scale > 1f) cornerRadius * scale else cornerRadius
            clipPath.addRoundRect(
                displayRect.left,
                displayRect.top,
                displayRect.right,
                displayRect.bottom,
                radius,
                radius,
                Path.Direction.CW
            )
        } else {
            clipPath.addRoundRect(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                cornerRadius,
                cornerRadius,
                Path.Direction.CW
            )
        }

        val save = canvas.save()
        canvas.clipPath(clipPath)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> pinching = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pinching = false
            else -> Unit
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        val zoomed = (photoView?.scale ?: 1f) > 1.01f
        // 缩放中或双指操作时把事件留给 PhotoView；1x 单指时交还 RecyclerView 翻页。
        super.requestDisallowInterceptTouchEvent(
            if (zoomed || pinching) disallowIntercept else false
        )
    }
}
