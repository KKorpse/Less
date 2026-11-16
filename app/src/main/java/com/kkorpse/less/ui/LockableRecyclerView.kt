package com.kkorpse.less.ui

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

/**
 * 手势互斥用的 RecyclerView：锁定时把「滚动」交给 LayoutManager 拦，
 * 这里只做转发——不能在这里拦触摸事件，否则 ItemTouchHelper 收不到
 * MOVE/UP，拖到一半就断了（这就是上一版卡死的原因）。
 */
class LockableRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    var horizontalScrollLocked: Boolean = false
        set(value) {
            field = value
            (layoutManager as? PeekingLinearLayoutManager)?.scrollLocked = value
            if (value) stopScroll()
        }
}
