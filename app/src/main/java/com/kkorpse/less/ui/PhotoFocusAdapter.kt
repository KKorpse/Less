package com.kkorpse.less.ui

import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import coil3.load
import coil3.request.crossfade
import coil3.request.error
import coil3.request.placeholder
import com.kkorpse.less.R
import com.kkorpse.less.data.MediaItem
import com.kkorpse.less.databinding.ItemPhotoFocusBinding
import com.kkorpse.less.databinding.ItemPhotoReviewBinding
import com.kkorpse.less.prefs.DoubleTapAction
import com.kkorpse.less.util.dp

class PhotoFocusAdapter(
    private val doubleTapAction: DoubleTapAction,
    private val onFavorite: (MediaItem) -> Unit,
    private val onTogglePending: (PendingDelete) -> Unit,
    private val onConfirmDelete: () -> Unit,
    private val onDiscardGroup: () -> Unit
) : ListAdapter<FocusPage, RecyclerView.ViewHolder>(Diff) {

    companion object {
        private const val TYPE_PHOTO = 0
        private const val TYPE_SUMMARY = 1
    }

    private var contentTop = 0
    private var contentBottom = 0

    /**
     * 照片可用的上下留白：顶部让出状态栏，底部让出收藏/时间/撤回这一排，
     * 让竖长图能一直长到按钮上方。
     */
    fun setContentInsets(top: Int, bottom: Int) {
        if (top == contentTop && bottom == contentBottom) return
        contentTop = top
        contentBottom = bottom
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is FocusPage.Photo -> TYPE_PHOTO
        is FocusPage.Summary -> TYPE_SUMMARY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_SUMMARY) {
            SummaryViewHolder(ItemPhotoReviewBinding.inflate(inflater, parent, false))
        } else {
            PhotoViewHolder(ItemPhotoFocusBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val page = getItem(position)) {
            is FocusPage.Photo -> (holder as PhotoViewHolder).bind(page.item)
            is FocusPage.Summary -> (holder as SummaryViewHolder).bind(page)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is PhotoViewHolder -> holder.dispose()
        }
        super.onViewRecycled(holder)
    }

    inner class PhotoViewHolder(
        private val binding: ItemPhotoFocusBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.card.photoView = binding.photo
            // 缩放/拖动时让圆角裁剪跟随图片重绘，否则硬件层会沿用 1x 时的裁剪框。
            binding.photo.setOnMatrixChangeListener {
                binding.card.invalidate()
            }
        }

        fun bind(item: MediaItem) {
            binding.card.scaleX = 1f
            binding.card.scaleY = 1f
            binding.card.alpha = 1f
            binding.progress.isVisible = true
            binding.error.isVisible = false
            binding.photo.contentDescription = item.name
            binding.photo.setScale(1f, false)
            binding.photo.setZoomable(true)

            // 图片可用区域：左右几乎贴边，上到状态栏下沿，下到收藏/时间/撤回上方。
            // PhotoView 在这块区域里按比例 fitCenter：方图抵满左右，长图抵满上下。
            val context = binding.root.context
            val sidePadding = context.dp(12)
            binding.root.setPadding(sidePadding, contentTop, sidePadding, contentBottom)
            binding.photo.setOnDoubleTapListener(object : GestureDetector.OnDoubleTapListener {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean = false

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (doubleTapAction == DoubleTapAction.FAVORITE) {
                        onFavorite(item)
                    } else {
                        val scale = binding.photo.scale
                        val target = when {
                            scale < binding.photo.mediumScale -> binding.photo.mediumScale
                            scale < binding.photo.maximumScale -> binding.photo.maximumScale
                            else -> binding.photo.minimumScale
                        }
                        binding.photo.setScale(target, e.x, e.y, true)
                    }
                    return true
                }

                override fun onDoubleTapEvent(e: MotionEvent): Boolean = false
            })

            binding.photo.load(item.uri) {
                crossfade(140)
                listener(
                    onSuccess = { _, _ ->
                        binding.progress.isVisible = false
                        binding.error.isVisible = false
                    },
                    onError = { _, _ ->
                        binding.progress.isVisible = false
                        binding.error.isVisible = true
                    }
                )
            }
        }

        fun dispose() {
            binding.photo.dispose()
        }
    }

    inner class SummaryViewHolder(
        private val binding: ItemPhotoReviewBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val gridAdapter = ReviewGridAdapter { entry -> onTogglePending(entry) }

        init {
            binding.reviewGrid.layoutManager = GridLayoutManager(
                binding.root.context,
                3
            )
            binding.reviewGrid.adapter = gridAdapter
            binding.reviewGrid.itemAnimator = null
        }

        fun bind(summary: FocusPage.Summary) {
            val selected = summary.pending.count { it.selected }
            val hasSelection = selected > 0
            val busy = summary.busy

            val context = binding.root.context
            binding.reviewSubtitle.text = context.getString(
                R.string.review_subtitle_count,
                summary.pending.size
            )

            binding.reviewConfirm.text = context.getString(R.string.review_confirm_n, selected)
            binding.reviewConfirm.isEnabled = hasSelection && !busy
            binding.reviewConfirm.alpha = if (binding.reviewConfirm.isEnabled) 1f else 0.4f
            binding.reviewDiscard.isEnabled = !busy
            if (busy) binding.reviewConfirm.setText(R.string.summary_busy)

            binding.reviewConfirm.setOnClickListener { onConfirmDelete() }
            binding.reviewDiscard.setOnClickListener { onDiscardGroup() }

            gridAdapter.submitList(summary.pending)
            updateGridHeight()
        }

        /** 卡片里固定露出 4 行，更多图片在网格内部滚动。 */
        private fun updateGridHeight() {
            val grid = binding.reviewGrid
            val gap = binding.root.context.dp(8)
            grid.post {
                val width = if (grid.width > 0) {
                    grid.width
                } else {
                    binding.reviewCard.width - binding.reviewCard.paddingStart -
                        binding.reviewCard.paddingEnd
                }
                if (width <= 0) return@post
                val cell = (width - gap * 2) / 3
                val height = cell * 4 + gap * 3
                if (grid.layoutParams.height != height) {
                    grid.layoutParams = grid.layoutParams.apply { this.height = height }
                }
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<FocusPage>() {
        override fun areItemsTheSame(oldItem: FocusPage, newItem: FocusPage): Boolean {
            return when {
                oldItem is FocusPage.Photo && newItem is FocusPage.Photo ->
                    oldItem.item.uri == newItem.item.uri

                oldItem is FocusPage.Summary && newItem is FocusPage.Summary -> true
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: FocusPage, newItem: FocusPage): Boolean =
            oldItem == newItem
    }
}
