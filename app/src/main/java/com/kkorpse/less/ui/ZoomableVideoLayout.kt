package com.kkorpse.less.ui

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

/**
 * 视频页的手势层：双指缩放、放大后单指平移、单击回调（播放 / 暂停）。
 *
 * - 缩放作用在唯一的子 View（视频帧）上，pivot 放在左上角，方便按焦点换算；
 * - 放大时不允许页面上下翻，缩回 1x 后把手势还给 ViewPager；
 * - 不允许缩到比原始尺寸更小（一过 1x 就停在 1x）。
 */
class ZoomableVideoLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 单击（用来暂停 / 继续）。 */
    var onTap: (() -> Unit)? = null

    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastX = 0f
    private var lastY = 0f
    /** 单指平移是否已经真正开始：多指抬起后要先校准一次，避免第一帧跳一下。 */
    private var panning = false
    private var animator: ValueAnimator? = null

    private val content: View? get() = getChildAt(0)

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                stopAnimation()
                panning = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                // 双指结束：剩下的那根手指重新作为平移起点，不沿用捏合时的坐标。
                panning = false
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleAround(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        }
    )

    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onTap?.invoke()
                return true
            }
        }
    )

    init {
        isClickable = true
    }

    /** 复用到别的视频时复位。 */
    fun reset() {
        stopAnimation()
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        applyTransform()
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    fun isZoomed(): Boolean = scale > 1.001f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        tapDetector.onTouchEvent(ev)
        scaleDetector.onTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopAnimation()
                panning = false
                lastX = ev.x
                lastY = ev.y
                parent?.requestDisallowInterceptTouchEvent(isZoomed())
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                panning = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && ev.pointerCount == 1 && isZoomed()) {
                    if (!panning) {
                        // 刚抬掉一根手指：先把起点对齐到剩下这根手指，再开始平移。
                        panning = true
                        lastX = ev.x
                        lastY = ev.y
                    } else {
                        offsetX += ev.x - lastX
                        offsetY += ev.y - lastY
                        clampOffsets()
                        applyTransform()
                        lastX = ev.x
                        lastY = ev.y
                    }
                } else {
                    lastX = ev.x
                    lastY = ev.y
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                panning = false
                lastX = ev.x
                lastY = ev.y
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                panning = false
                settle()
            }
        }
        return true
    }

    private fun scaleAround(factor: Float, focusX: Float, focusY: Float) {
        val target = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        val ratio = target / scale
        offsetX = focusX - (focusX - offsetX) * ratio
        offsetY = focusY - (focusY - offsetY) * ratio
        scale = target
        clampOffsets()
        applyTransform()
    }

    private fun clampOffsets() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (scale <= 1f) {
            // 比原始尺寸还小：居中显示，松手再弹回去。
            offsetX = (w - w * scale) / 2f
            offsetY = (h - h * scale) / 2f
        } else {
            // 放大后不允许露出空白：内容必须盖住整个可视区。
            offsetX = offsetX.coerceIn(w * (1f - scale), 0f)
            offsetY = offsetY.coerceIn(h * (1f - scale), 0f)
        }
    }

    private fun settle() {
        if (scale < 1f) {
            animateTo(1f)
        } else {
            clampOffsets()
            applyTransform()
        }
        if (!isZoomed() && animator == null) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
    }

    private fun animateTo(target: Float) {
        stopAnimation()
        val startScale = scale
        val startX = offsetX
        val startY = offsetY
        val endX = if (target <= 1f) (width - width * target) / 2f else offsetX
        val endY = if (target <= 1f) (height - height * target) / 2f else offsetY
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                scale = startScale + (target - startScale) * f
                offsetX = startX + (endX - startX) * f
                offsetY = startY + (endY - startY) * f
                applyTransform()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    animator = null
                    clampOffsets()
                    applyTransform()
                    if (!isZoomed()) parent?.requestDisallowInterceptTouchEvent(false)
                }
            })
            start()
        }
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    private fun applyTransform() {
        content?.apply {
            pivotX = 0f
            pivotY = 0f
            scaleX = scale
            scaleY = scale
            translationX = offsetX
            translationY = offsetY
        }
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    private companion object {
        /** 最小就是原始尺寸：比 1x 小一律回到 1x。 */
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 4f
    }
}
