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

    // 注意：锁定时必须「接住」事件（返回 true），不能返回 false。
    // 返回 false 会把事件让给子 View（PhotoView），ItemTouchHelper 拿不到后续
    // ACTION_MOVE/UP，拖到一半就断了，锁也解不掉——表现就是上滑卡死。
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (horizontalScrollLocked) return true
        return super.onInterceptTouchEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // 接住但不滚动；ItemTouchHelper 仍然通过 OnItemTouchListener 收到事件。
        if (horizontalScrollLocked) return true
        return super.onTouchEvent(e)
    }
}
