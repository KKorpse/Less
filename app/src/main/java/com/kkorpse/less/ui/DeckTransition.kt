package com.kkorpse.less.ui

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * 翻页/删除共用的「发牌」过渡。
 *
 * 一套模型，两个方向：
 * - 横向：左滑翻页（由 RecyclerView 滚动驱动进度）
 * - 纵向：上滑删除（由 ValueAnimator 驱动进度）
 *
 * 进度 p = 0 表示刚开始，p = 1 表示完成。
 * 离开的卡片沿轴向滑出屏幕，进入的卡片钉在中间、由下方浮起并放大。
 */
object DeckTransition {

    /** 进入卡片起始时下移的距离（dp）。 */
    const val LIFT_DP = 72f
    const val MIN_SCALE = 0.94f
    const val DURATION_MS = 260L

    /** 离开的卡片：沿轴向滑出。横向的位移交给 RecyclerView，这里不动它。 */
    fun applyOutgoing(view: View, card: FocusCardLayout, p: Float, vertical: Boolean) {
        view.translationZ = 2f
        if (vertical) {
            view.translationX = 0f
            view.translationY = -p * view.height
        } else {
            // 横向由 RecyclerView 的滚动负责位移，这里只保证层级与状态干净
            view.translationY = 0f
        }
        card.alpha = 1f
        card.scaleX = 1f
        card.scaleY = 1f
    }

    /**
     * 进入的卡片：钉到容器中间，从下方浮起 + 放大。
     * @param opaque 是否始终不透明（删除时用 true，避免底下露出黑底）
     */
    fun applyIncoming(
        view: View,
        card: FocusCardLayout,
        p: Float,
        vertical: Boolean,
        liftPx: Float,
        opaque: Boolean,
        fromScale: Float = MIN_SCALE
    ) {
        view.translationZ = 0f
        view.translationX = -view.left.toFloat()
        if (vertical) {
            view.translationY = 0f
            card.translationY = (1f - p) * liftPx
        } else {
            view.translationY = (1f - p) * liftPx
        }
        val scale = fromScale + (1f - fromScale) * p
        card.scaleX = scale
        card.scaleY = scale
        card.alpha = if (opaque) 1f else p.coerceIn(0f, 1f)
    }

    /** 复位，避免影响下一次复用。 */
    fun reset(view: View, card: FocusCardLayout) {
        view.translationX = 0f
        view.translationY = 0f
        view.translationZ = 0f
        card.translationX = 0f
        card.translationY = 0f
        card.scaleX = 1f
        card.scaleY = 1f
        card.alpha = 1f
    }

    /** 纵向（删除）用的进度动画。 */
    fun animate(
        from: Float,
        to: Float,
        onUpdate: (Float) -> Unit,
        onEnd: () -> Unit,
        duration: Long = DURATION_MS
    ): ValueAnimator {
        return ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener { onUpdate(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                // 被取消（用户中途重新按下）时不算走完，避免提前标记删除。
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled) onEnd()
                }
            })
            start()
        }
    }
}
