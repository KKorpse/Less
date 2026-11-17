package com.kkorpse.less.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import com.kkorpse.less.data.MediaItem
import com.kkorpse.less.prefs.DoubleTapAction
import kotlin.math.abs
import kotlin.math.ceil

/**
 * 照片专注的牌堆：一次只摆两张牌（当前 + 下一张），用同一个进度 p 驱动全部动效。
 *
 * - 横向：左滑下一张 / 右滑上一张；
 * - 纵向：上滑删除。
 *
 * 拖动时 p 由手指决定，松手后 p 由动画走完；渲染只走 [applyProgress]，
 * 位置也由我们自己摆，所以"看到的"和"点到的"永远是同一张。
 */
class FocusDeckView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    interface Listener {
        /** 停在某一页上（含初始化）。 */
        fun onPageSettled(position: Int)

        /** 上滑动画走完，可以删掉这张了。 */
        fun onDeleteCommitted(page: FocusPage.Photo)

        /** 上滑拖动进度，用于顶部红光。 */
        fun onDeleteProgress(progress: Float)
    }

    var listener: Listener? = null

    private var front = DeckCardView(context)
    private var back = DeckCardView(context)

    private var pages: List<FocusPage> = emptyList()
    private var doubleTapAction = DoubleTapAction.ZOOM
    private var onFavorite: ((MediaItem) -> Unit)? = null

    /** 当前停在第几页。 */
    var currentIndex: Int = 0
        private set

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val velocity = VelocityTracker.obtain()

    private var axis = Axis.NONE
    private var forward = true
    private var progress = 0f
    private var baseProgress = 0f
    /** 上一次收尾/当前拖动的前进方向，用于中途接管时判断能否续上进度。 */
    private var baseForward = true
    private var settle: ValueAnimator? = null

    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var ignore = false

    init {
        addView(front)
        addView(back)
        front.touchEnabled = true
        back.touchEnabled = false
        front.bringToFront()
    }

    // ---------------------------------------------------------------- 数据

    fun setPhotoActions(doubleTapAction: DoubleTapAction, onFavorite: (MediaItem) -> Unit) {
        this.doubleTapAction = doubleTapAction
        this.onFavorite = onFavorite
    }

    fun setFavoritesMode(value: Boolean) {
        front.setFavoritesMode(value)
        back.setFavoritesMode(value)
    }

    fun setSummaryCallbacks(callbacks: DeckCardView.SummaryCallbacks?) {
        front.setSummaryCallbacks(callbacks)
        back.setSummaryCallbacks(callbacks)
    }

    fun setContentInsets(top: Int, bottom: Int) {
        front.setContentInsets(top, bottom)
        back.setContentInsets(top, bottom)
    }

    /**
     * 换一批页。
     * @param index 指定停在第几页；传 -1 表示尽量停在原来那张（按 URI 匹配），
     *              原来那张没了就停在原来的下标。
     */
    fun submit(pages: List<FocusPage>, index: Int = -1) {
        val previousUri = (this.pages.getOrNull(currentIndex) as? FocusPage.Photo)
            ?.item?.uri?.toString()
        this.pages = pages
        val target = when {
            index >= 0 -> index
            previousUri != null -> pages.indexOfFirst {
                it is FocusPage.Photo && it.item.uri.toString() == previousUri
            }.takeIf { it >= 0 } ?: currentIndex
            else -> currentIndex
        }
        currentIndex = target.coerceIn(0, pages.lastIndex.coerceAtLeast(0))

        settle?.cancel()
        settle = null
        axis = Axis.NONE
        forward = true
        progress = 0f
        baseProgress = 0f
        front.clearTransform()
        back.clearTransform()
        bindSides()
        listener?.onPageSettled(currentIndex)
    }

    private fun currentPage(): FocusPage? = pages.getOrNull(currentIndex)

    fun dispose() {
        settle?.cancel()
        settle = null
        velocity.recycle()
        front.dispose()
        back.dispose()
    }

    // ---------------------------------------------------------------- 渲染

    private fun bindSides() {
        front.bind(pages.getOrNull(currentIndex), doubleTapAction, onFavorite ?: {})
        back.bind(pages.getOrNull(currentIndex + 1), doubleTapAction, onFavorite ?: {})
        applyProgress()
    }

    private fun applyProgress() {
        val p = progress
        when (axis) {
            Axis.PAGE -> {
                front.translationX = if (forward) -p * width else p * width
                front.translationY = 0f
            }

            Axis.DELETE -> {
                front.translationX = 0f
                front.translationY = -p * height
            }

            Axis.NONE -> {
                front.translationX = 0f
                front.translationY = 0f
            }
        }
        front.scaleX = 1f
        front.scaleY = 1f
        front.alpha = 1f
        back.applyIncoming(if (axis == Axis.NONE) 0f else p)
        // 红光跟着进度走，回弹时也会一起暗下去。
        if (axis == Axis.DELETE) listener?.onDeleteProgress(p)
    }

    /**
     * 交换两张牌：翻过去之后，本来浮起来的那张就地变成当前牌，
     * 位置和图片都不用重新搬，所以不会闪。
     */
    private fun swapCards() {
        val previous = front
        front = back
        back = previous
        front.touchEnabled = true
        back.touchEnabled = false
        front.bringToFront()
        back.clearTransform()
    }

    // ---------------------------------------------------------------- 手势

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                tracking = true
                ignore = false
                velocity.clear()
                velocity.addMovement(ev)
                // 收尾动画不在这里取消：单击（双击的第一下）不应该打断它；
                // 真正开始拖动时才从当前进度接管。
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!tracking || ignore) return axis != Axis.NONE
                val dx = ev.x - downX
                val dy = downY - ev.y
                if (abs(dx) < touchSlop && abs(dy) < touchSlop) return axis != Axis.NONE
                if (axis != Axis.NONE) return true
                if (front.isZoomed) {
                    // 放大状态下拖动是平移图片，不翻页也不删除。
                    ignore = true
                    return false
                }
                if (abs(dx) > abs(dy) * DIRECTION_BIAS) {
                    val next = dx < 0f
                    if (next && !canForward()) {
                        ignore = true
                        return false
                    }
                    if (!next && !canBackward()) {
                        ignore = true
                        return false
                    }
                    beginPage(next, dx)
                    return true
                }
                if (dy > abs(dx) * DIRECTION_BIAS) {
                    if (!canDelete()) {
                        // 汇总页等非照片页不做上滑删除，纵向手势还给内容自己滚。
                        ignore = true
                        return false
                    }
                    beginDelete(dy)
                    return true
                }
                return false
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracking = false
                velocity.addMovement(ev)
            }
        }
        return axis != Axis.NONE
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (axis == Axis.NONE) return super.onTouchEvent(ev)
        val size = if (axis == Axis.PAGE) width else height
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                velocity.addMovement(ev)
                when (axis) {
                    Axis.PAGE -> {
                        val dx = ev.x - downX
                        val travel = (if (forward) -dx else dx).coerceAtLeast(0f)
                        progress = (baseProgress + travel / size.coerceAtLeast(1))
                            .coerceIn(0f, 1f)
                        applyProgress()
                    }

                    Axis.DELETE -> {
                        val dy = (downY - ev.y).coerceAtLeast(0f)
                        progress = (baseProgress + dy / size.coerceAtLeast(1))
                            .coerceIn(0f, 1f)
                        applyProgress()
                    }

                    Axis.NONE -> Unit
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity.addMovement(ev)
                velocity.computeCurrentVelocity(1000)
                val towardsNext = when (axis) {
                    Axis.PAGE -> if (forward) -velocity.xVelocity else velocity.xVelocity
                    Axis.DELETE -> -velocity.yVelocity
                    Axis.NONE -> 0f
                }
                val cancelled = ev.actionMasked == MotionEvent.ACTION_CANCEL
                finishDrag(if (cancelled) 0f else towardsNext, cancelled)
            }
        }
        return true
    }

    private fun beginPage(next: Boolean, dx: Float) {
        takeOverFromSettle(next)
        axis = Axis.PAGE
        forward = next
        // 每次起手都重新绑定"浮起来的这张"：上一次可能拖到一半取消了，
        // 它上面还留着上一张/下一张，直接复用会浮出错的照片。
        val incoming = if (next) currentIndex + 1 else currentIndex - 1
        back.bind(pages.getOrNull(incoming), doubleTapAction, onFavorite ?: {})
        val travel = (if (next) -dx else dx).coerceAtLeast(0f)
        progress = (baseProgress + travel / width.coerceAtLeast(1)).coerceIn(0f, 1f)
        applyProgress()
    }

    private fun beginDelete(dy: Float) {
        takeOverFromSettle(true)
        axis = Axis.DELETE
        forward = true
        back.bind(pages.getOrNull(currentIndex + 1), doubleTapAction, onFavorite ?: {})
        progress = (baseProgress + dy / height.coerceAtLeast(1)).coerceIn(0f, 1f)
        applyProgress()
    }

    /**
     * 如果上一次收尾还没走完就又按下，从它停下的进度接着拖；
     * 方向变了就从头开始，避免进度和手势对不上。
     */
    private fun takeOverFromSettle(nextForward: Boolean) {
        val running = settle
        if (running != null) {
            running.cancel()
            settle = null
            baseProgress = if (baseForward == nextForward) progress else 0f
        } else {
            baseProgress = if (progress > 0f && baseForward == nextForward) progress else 0f
        }
    }

    private fun finishDrag(speed: Float, cancelled: Boolean) {
        val commit = !cancelled && when (axis) {
            Axis.PAGE -> progress >= PAGE_THRESHOLD || speed > FLING_VELOCITY
            Axis.DELETE -> progress >= DELETE_THRESHOLD || speed > FLING_VELOCITY
            Axis.NONE -> false
        }
        val target = if (commit) 1f else 0f
        val size = if (axis == Axis.PAGE) width else height
        val remaining = (abs(target - progress) * size).toInt()
        val finishedAxis = axis
        val finishedForward = forward
        val deleting = if (finishedAxis == Axis.DELETE) {
            pages.getOrNull(currentIndex) as? FocusPage.Photo
        } else {
            null
        }

        settle?.cancel()
        baseForward = finishedForward
        settle = ValueAnimator.ofFloat(progress, target).apply {
            duration = decelerationDuration(remaining)
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                applyProgress()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var aborted = false

                override fun onAnimationCancel(animation: Animator) {
                    aborted = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (aborted) return
                    settle = null
                    if (target <= 0f) {
                        axis = Axis.NONE
                        progress = 0f
                        baseProgress = 0f
                        applyProgress()
                        listener?.onDeleteProgress(0f)
                        return
                    }
                    finishCommitted(finishedAxis, finishedForward, deleting)
                }
            })
            start()
        }
    }

    private fun finishCommitted(
        finishedAxis: Axis,
        finishedForward: Boolean,
        deleting: FocusPage.Photo?
    ) {
        swapCards()
        currentIndex = if (finishedAxis == Axis.PAGE && !finishedForward) {
            (currentIndex - 1).coerceAtLeast(0)
        } else {
            (currentIndex + 1).coerceAtMost(pages.lastIndex.coerceAtLeast(0))
        }
        axis = Axis.NONE
        progress = 0f
        baseProgress = 0f
        bindSides()
        listener?.onPageSettled(currentIndex)
        if (finishedAxis == Axis.DELETE) {
            listener?.onDeleteProgress(0f)
            if (deleting != null) listener?.onDeleteCommitted(deleting)
        }
    }

    private fun canForward(): Boolean = currentIndex < pages.lastIndex

    private fun canBackward(): Boolean = currentIndex > 0

    private fun canDelete(): Boolean = currentPage() is FocusPage.Photo

    /** 和 RecyclerView 的 LinearSmoothScroller 同一套换算：25ms/英寸 ÷ densityDpi，再 /0.3356。 */
    private fun decelerationDuration(distancePx: Int): Long {
        val densityDpi = resources.displayMetrics.densityDpi.toFloat().coerceAtLeast(1f)
        val millisPerPixel = 25f / densityDpi
        val linear = ceil(abs(distancePx) * millisPerPixel)
        return ceil(linear / 0.3356f).toLong().coerceAtLeast(1L)
    }

    private enum class Axis { NONE, PAGE, DELETE }

    private companion object {
        /** 越接近 1 越难触发竖直手势，避免斜着划就变成删除。 */
        const val DIRECTION_BIAS = 1.2f
        /** 翻页：超过半页，或者甩得够快。 */
        const val PAGE_THRESHOLD = 0.5f
        /** 删除：滑到卡片高度 8%，或者甩得够快。 */
        const val DELETE_THRESHOLD = 0.08f
        const val FLING_VELOCITY = 900f
    }
}
