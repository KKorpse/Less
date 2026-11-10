package com.kkorpse.less.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * 正方形网格单元。宽度由 GridLayoutManager 决定，高度跟随宽度。
 */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        )
    }
}
