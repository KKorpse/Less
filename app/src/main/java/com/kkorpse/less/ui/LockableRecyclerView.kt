package com.kkorpse.less.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView

/**
 * 手势互斥：竖直拖动 / 删除收尾期间锁住横向滚动，
 * 避免上滑到一半被横向事件接管。
 *
 * ItemTouchHelper 是通过 dispatchTouchEvent 里的 OnItemTouchListener 收事件的，
 * 这里只拦自己的滚动，不影响它。
 */
class LockableRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    var horizontalScrollLocked: Boolean = false
        set(value) {
            field = value
            if (value) stopScroll()
        }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (horizontalScrollLocked) return false
        return super.onInterceptTouchEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (horizontalScrollLocked) return false
        return super.onTouchEvent(e)
    }
}
