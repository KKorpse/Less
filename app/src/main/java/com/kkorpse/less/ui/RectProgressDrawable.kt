package com.kkorpse.less.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.kkorpse.less.R

/**
 * 圆角卡片背景，边框按进度加粗：从左上角开始顺时针走，走到哪就粗到哪，100% 是整圈。
 * 用来把统计页的两张卡片变成进度条。
 */
class RectProgressDrawable(context: Context) : Drawable() {

    private val density = context.resources.displayMetrics.density
    private val radiusPx = RADIUS_DP * density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.surface)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x14FFFFFF
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.accent_blue)
    }
    private val glowPaint = Paint(progressPaint).apply {
        strokeWidth = 7f * density
        color = Color.argb(46, 143, 168, 255)
    }

    private val rect = RectF()
    private val progressPath = Path()
    private val measure = PathMeasure()
    private val segment = Path()
    private var animator: ValueAnimator? = null

    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidateSelf()
        }

    fun animateTo(target: Float) {
        val value = target.coerceIn(0f, 1f)
        animator?.cancel()
        if (progress == value) return
        animator = ValueAnimator.ofFloat(progress, value).apply {
            duration = 480L
            interpolator = DecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float }
            start()
        }
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val inset = progressPaint.strokeWidth / 2f
        rect.set(
            bounds.left + inset,
            bounds.top + inset,
            bounds.right - inset,
            bounds.bottom - inset
        )
        progressPath.reset()
        progressPath.addRoundRect(rect, radiusPx - inset, radiusPx - inset, Path.Direction.CW)
        measure.setPath(progressPath, false)
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        canvas.drawRoundRect(
            bounds.left.toFloat(),
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.bottom.toFloat(),
            radiusPx,
            radiusPx,
            fillPaint
        )

        val borderInset = borderPaint.strokeWidth / 2f
        canvas.drawRoundRect(
            bounds.left + borderInset,
            bounds.top + borderInset,
            bounds.right - borderInset,
            bounds.bottom - borderInset,
            radiusPx - borderInset,
            radiusPx - borderInset,
            borderPaint
        )

        if (progress <= 0.001f) return
        segment.reset()
        measure.getSegment(0f, measure.length * progress, segment, true)
        canvas.drawPath(segment, glowPaint)
        canvas.drawPath(segment, progressPaint)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    private companion object {
        const val RADIUS_DP = 20f
    }
}
