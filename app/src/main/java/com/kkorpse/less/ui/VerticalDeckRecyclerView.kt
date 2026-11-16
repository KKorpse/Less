package com.kkorpse.less.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * 只负责「竖直上滑」这一种手势的 RecyclerView。
 *
 * 不滚动、不接管横向：一旦判定为竖直手势就吃掉事件，
 * 通过回调把进度交给外部（拖动和收尾共用同一份进度）。
 */
class VerticalDeckRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    interface Listener {
        /** 第一次判定为竖直上滑。 */
        fun onDeckDragStart()

        /** 拖动进度变化：progress 0..1，dy 为已上滑的像素。 */
        fun onDeckDrag(progress: Float, dy: Float)

        /** 松手：progress 当前进度，velocityY 为抬手速度（向上为负）。 */
        fun onDeckRelease(progress: Float, velocityY: Float)
    }

    var listener: Listener? = null

    /** 只有当前页是照片时才允许上滑。 */
    var deckEnabled: Boolean = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var vertical = false
    private var tracking = false
    private var lastProgress = 0f
    private val tracker = VelocityTracker.obtain()

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (!deckEnabled || listener == null) return super.onInterceptTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                vertical = false
                tracking = true
                lastProgress = 0f
                tracker.clear()
                tracker.addMovement(e)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!vertical && tracking) {
                    val dx = e.x - downX
                    val dy = downY - e.y
                    if (dy > touchSlop && dy > abs(dx) * 1.2f) {
                        vertical = true
                        listener?.onDeckDragStart()
                        return true
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tracking = false
        }
        return super.onInterceptTouchEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!vertical) return super.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                tracker.addMovement(e)
                val dy = (downY - e.y).coerceAtLeast(0f)
                lastProgress = (dy / height.coerceAtLeast(1)).coerceIn(0f, 1f)
                listener?.onDeckDrag(lastProgress, dy)
            }

            MotionEvent.ACTION_UP -> {
                tracker.addMovement(e)
                tracker.computeCurrentVelocity(1000)
                val velocity = tracker.yVelocity
                vertical = false
                tracking = false
                listener?.onDeckRelease(lastProgress, velocity)
            }

            MotionEvent.ACTION_CANCEL -> {
                vertical = false
                tracking = false
                listener?.onDeckRelease(lastProgress, 0f)
            }
        }
        return true
    }
}
