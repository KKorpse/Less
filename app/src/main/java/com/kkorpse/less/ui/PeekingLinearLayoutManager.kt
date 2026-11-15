package com.kkorpse.less.ui

import android.content.Context
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 横向分页用的 LayoutManager：额外预布局一屏，让「下一张」始终已经存在。
 *
 * 这样上滑删除时，下一张是真实存在的视图，可以直接被揭开；
 * 否则下一张要等滚动/数据变化才被创建，动画里就只剩底色。
 */
class PeekingLinearLayoutManager(context: Context) :
    LinearLayoutManager(context, HORIZONTAL, false) {

    override fun getExtraLayoutSpace(state: RecyclerView.State?): Int = width
}
