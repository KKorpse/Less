package com.kkorpse.less.ui

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import coil3.dispose
import coil3.load
import coil3.request.allowHardware
import coil3.request.crossfade
import com.github.chrisbanes.photoview.PhotoView
import com.kkorpse.less.Diagnostics
import com.kkorpse.less.R
import com.kkorpse.less.data.MediaItem
import com.kkorpse.less.databinding.ItemPhotoFocusBinding
import com.kkorpse.less.databinding.ItemPhotoReviewBinding
import com.kkorpse.less.prefs.DoubleTapAction
import com.kkorpse.less.util.dp

/**
 * 牌堆里的一张牌：要么是一张照片，要么是组尾的汇总卡片。
 *
 * 位置、缩放、透明度全部由 [FocusDeckView] 用同一个进度驱动，这里只负责内容和
 * 触摸开关，避免出现"画在一处、点在另一处"。
 */
class DeckCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    interface SummaryCallbacks {
        fun onTogglePending(entry: PendingDelete)
        fun onConfirmDelete()
        fun onDiscardGroup()
    }

    private val photoCard = ItemPhotoFocusBinding.inflate(LayoutInflater.from(context), this, false)
    private val reviewCard = ItemPhotoReviewBinding.inflate(LayoutInflater.from(context), this, false)
    private val gridAdapter = ReviewGridAdapter { entry -> summaryCallbacks?.onTogglePending(entry) }

    private var summaryCallbacks: SummaryCallbacks? = null
    private var contentTop = 0
    private var contentBottom = 0
    private var doubleTapAction = DoubleTapAction.ZOOM
    private var onFavorite: ((MediaItem) -> Unit)? = null

    /** 只有当前这张接收触摸，浮起来的那张永远不接收。 */
    var touchEnabled: Boolean = true

    /** 收藏模式的汇总卡片：文案从「删除」改成「移出收藏」。 */
    private var favoritesMode = false

    /** 当前绑定的页；同一个对象重复 bind 会直接跳过，避免重复解码。 */
    var page: FocusPage? = null
        private set

    val isZoomed: Boolean
        get() = page is FocusPage.Photo && photoCard.photo.scale > 1.01f

    init {
        addView(photoCard.root)
        addView(reviewCard.root)
        photoCard.root.isVisible = false
        reviewCard.root.isVisible = false
        reviewCard.reviewGrid.layoutManager = GridLayoutManager(context, 3)
        reviewCard.reviewGrid.adapter = gridAdapter
        reviewCard.reviewGrid.itemAnimator = null
        // 缩放/拖动时让圆角裁剪跟着图片重绘，否则硬件层会沿用 1x 时的裁剪框。
        photoCard.card.photoView = photoCard.photo
        photoCard.photo.setOnMatrixChangeListener { photoCard.card.invalidate() }
        photoCard.photo.setZoomable(true)
    }

    fun setSummaryCallbacks(callbacks: SummaryCallbacks?) {
        summaryCallbacks = callbacks
    }

    fun setFavoritesMode(value: Boolean) {
        if (favoritesMode == value) return
        favoritesMode = value
        (page as? FocusPage.Summary)?.let { bindSummary(it) }
    }

    fun setContentInsets(top: Int, bottom: Int) {
        if (top == contentTop && bottom == contentBottom) return
        contentTop = top
        contentBottom = bottom
        applyPhotoPadding()
    }

    fun bind(
        page: FocusPage?,
        doubleTapAction: DoubleTapAction,
        onFavorite: (MediaItem) -> Unit
    ) {
        if (page === this.page) {
            // 内容没变，只同步一次回调，避免重复解码。
            this.doubleTapAction = doubleTapAction
            this.onFavorite = onFavorite
            return
        }
        this.page = page
        this.doubleTapAction = doubleTapAction
        this.onFavorite = onFavorite
        when (page) {
            is FocusPage.Photo -> bindPhoto(page.item)
            is FocusPage.Summary -> bindSummary(page)
            null -> clear()
        }
    }

    /** 作为"下一张"时的样子：钉在原位，从下方浮起、放大、淡入。 */
    fun applyIncoming(progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        alpha = p
        val scale = MIN_SCALE + (1f - MIN_SCALE) * p
        scaleX = scale
        scaleY = scale
        translationX = 0f
        translationY = (1f - p) * liftPx()
    }

    fun clearTransform() {
        translationX = 0f
        translationY = 0f
        scaleX = 1f
        scaleY = 1f
        alpha = 1f
    }

    fun dispose() {
        photoCard.photo.dispose()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean =
        if (touchEnabled) super.dispatchTouchEvent(ev) else false

    private fun bindPhoto(item: MediaItem) {
        reviewCard.root.isVisible = false
        photoCard.root.isVisible = true
        clearTransform()
        photoCard.card.alpha = 1f
        photoCard.card.translationX = 0f
        photoCard.card.translationY = 0f
        photoCard.card.scaleX = 1f
        photoCard.card.scaleY = 1f
        photoCard.progress.isVisible = true
        photoCard.error.isVisible = false
        photoCard.photo.contentDescription = item.name
        photoCard.photo.setScale(1f, false)
        photoCard.photo.setZoomable(true)
        applyPhotoPadding()
        photoCard.photo.setOnDoubleTapListener(object : GestureDetector.OnDoubleTapListener {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean = false

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (doubleTapAction == DoubleTapAction.FAVORITE) {
                    onFavorite?.invoke(item)
                } else {
                    val scale = photoCard.photo.scale
                    val target = when {
                        scale < photoCard.photo.mediumScale -> photoCard.photo.mediumScale
                        scale < photoCard.photo.maximumScale -> photoCard.photo.maximumScale
                        else -> photoCard.photo.minimumScale
                    }
                    photoCard.photo.setScale(target, e.x, e.y, true)
                }
                return true
            }

            override fun onDoubleTapEvent(e: MotionEvent): Boolean = false
        })

        photoCard.photo.load(item.uri) {
            // 部分 ROM 上 HDR/10bit 图片用 hardware bitmap 会整块黑屏，这里禁用。
            allowHardware(false)
            crossfade(140)
            listener(
                onSuccess = { _, _ ->
                    photoCard.progress.isVisible = false
                    photoCard.error.isVisible = false
                },
                onError = { _, result ->
                    photoCard.progress.isVisible = false
                    photoCard.error.isVisible = true
                    Diagnostics.recordError(item.uri.toString(), result.throwable)
                }
            )
        }
    }

    private fun bindSummary(summary: FocusPage.Summary) {
        photoCard.root.isVisible = false
        reviewCard.root.isVisible = true
        clearTransform()

        val context = reviewCard.root.context
        val selected = summary.pending.count { it.selected }
        val hasSelection = selected > 0
        val busy = summary.busy

        reviewCard.reviewSubtitle.text = if (favoritesMode) {
            context.getString(R.string.review_subtitle_unfavorite)
        } else {
            context.getString(R.string.review_subtitle_count, summary.pending.size)
        }
        reviewCard.reviewConfirm.text = if (favoritesMode) {
            context.getString(R.string.review_confirm_unfavorite, selected)
        } else {
            context.getString(R.string.review_confirm_n, selected)
        }
        reviewCard.reviewConfirm.isEnabled = hasSelection && !busy
        reviewCard.reviewConfirm.alpha = if (reviewCard.reviewConfirm.isEnabled) 1f else 0.4f
        reviewCard.reviewDiscard.isEnabled = !busy
        if (busy) reviewCard.reviewConfirm.setText(R.string.summary_busy)
        reviewCard.reviewConfirm.setOnClickListener { summaryCallbacks?.onConfirmDelete() }
        reviewCard.reviewDiscard.setOnClickListener { summaryCallbacks?.onDiscardGroup() }

        gridAdapter.submitList(summary.pending)
        updateGridHeight()
    }

    private fun clear() {
        dispose()
        photoCard.root.isVisible = false
        reviewCard.root.isVisible = false
    }

    private fun applyPhotoPadding() {
        val side = context.dp(12)
        photoCard.root.setPadding(side, contentTop, side, contentBottom)
    }

    /** 卡片里固定露出 4 行，更多图片在网格内部滚动。 */
    private fun updateGridHeight() {
        val grid = reviewCard.reviewGrid
        val gap = context.dp(8)
        grid.post {
            val width = if (grid.width > 0) {
                grid.width
            } else {
                reviewCard.reviewCard.width - reviewCard.reviewCard.paddingStart -
                    reviewCard.reviewCard.paddingEnd
            }
            if (width <= 0) return@post
            val cell = (width - gap * 2) / 3
            val height = cell * 4 + gap * 3
            if (grid.layoutParams.height != height) {
                grid.layoutParams = grid.layoutParams.apply { this.height = height }
            }
        }
    }

    private fun liftPx(): Float = context.dp(72).toFloat()

    private companion object {
        /** 进入卡片起始时的缩放。 */
        const val MIN_SCALE = 0.94f
    }
}
