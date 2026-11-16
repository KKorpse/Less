package com.kkorpse.less.ui

import android.content.Context
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 横向分页用的 LayoutManager：
 * 1. 额外预布局一屏，让「下一张」始终已经存在；
 * 2. 支持「锁滚动」——锁住时返回 0，RecyclerView 的触摸事件流不受影响，
 *    这样 ItemTouchHelper 仍然能收到完整的 DOWN/MOVE/UP。
 */
class PeekingLinearLayoutManager(context: Context) :
    LinearLayoutManager(context, HORIZONTAL, false) {

    override fun getExtraLayoutSpace(state: RecyclerView.State?): Int = width

}
