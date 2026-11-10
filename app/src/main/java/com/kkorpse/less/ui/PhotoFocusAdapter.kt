package com.kkorpse.less.ui

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import coil3.load
import coil3.request.crossfade
import coil3.request.error
import coil3.request.placeholder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import com.kkorpse.less.R
import com.kkorpse.less.data.MediaItem
import com.kkorpse.less.databinding.ItemGroupSummaryBinding
import com.kkorpse.less.databinding.ItemPhotoFocusBinding
import com.kkorpse.less.prefs.DoubleTapAction
import com.kkorpse.less.util.dp

class PhotoFocusAdapter(
    private val doubleTapAction: DoubleTapAction,
    private val onFavorite: (MediaItem) -> Unit,
    private val onBatchDelete: () -> Unit,
    private val onFinish: () -> Unit,
    private val onUndoBatch: () -> Unit,
    private val onReview: () -> Unit
) : ListAdapter<FocusPage, RecyclerView.ViewHolder>(Diff) {

    companion object {
        private const val TYPE_PHOTO = 0
        private const val TYPE_SUMMARY = 1
        private const val MAX_SUMMARY_THUMBS = 6
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is FocusPage.Photo -> TYPE_PHOTO
        is FocusPage.Summary -> TYPE_SUMMARY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_SUMMARY) {
            SummaryViewHolder(ItemGroupSummaryBinding.inflate(inflater, parent, false))
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
            is SummaryViewHolder -> holder.disposeThumbs()
        }
        super.onViewRecycled(holder)
    }

    inner class PhotoViewHolder(
        private val binding: ItemPhotoFocusBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.card.clipToOutline = true
            binding.card.outlineProvider = ViewOutlineProvider.BACKGROUND
            binding.card.photoView = binding.photo
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
            binding.background.load(item.uri) {
                crossfade(180)
                placeholder(R.drawable.bg_thumb_placeholder)
                size(256, 256)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                binding.background.setRenderEffect(
                    RenderEffect.createBlurEffect(36f, 36f, Shader.TileMode.CLAMP)
                )
            }
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
            binding.background.dispose()
        }
    }

    inner class SummaryViewHolder(
        private val binding: ItemGroupSummaryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(summary: FocusPage.Summary) {
            val deleting = summary.deleted.isNotEmpty()
            val items = if (deleting) summary.deleted.map { it.item } else summary.pending.map { it.item }

            binding.summaryTitle.text = binding.root.context.getString(
                if (deleting) R.string.summary_title_deleted else R.string.summary_title_done
            )
            binding.summarySubtitle.text = when {
                deleting -> binding.root.context.getString(
                    R.string.summary_deleted_count,
                    summary.deleted.size
                )

                summary.pending.isNotEmpty() -> binding.root.context.getString(
                    R.string.summary_pending,
                    summary.pending.size
                )

                else -> binding.root.context.getString(R.string.summary_empty)
            }
            binding.summaryGroupInfo.text = binding.root.context.getString(
                R.string.summary_group_info,
                summary.groupSize
            )

            bindThumbnails(items)

            val primary = binding.summaryPrimary
            val secondary = binding.summarySecondary
            primary.isEnabled = !summary.busy
            secondary.isEnabled = !summary.busy

            when {
                deleting -> {
                    primary.setText(R.string.summary_undo_delete)
                    secondary.setText(R.string.summary_finish)
                    primary.setOnClickListener { onUndoBatch() }
                    secondary.setOnClickListener { onFinish() }
                }

                summary.pending.isNotEmpty() -> {
                    primary.text = binding.root.context.getString(
                        R.string.summary_delete_n,
                        summary.pending.size
                    )
                    secondary.setText(R.string.summary_keep)
                    primary.setOnClickListener { onBatchDelete() }
                    secondary.setOnClickListener { onFinish() }
                }

                else -> {
                    primary.setText(R.string.summary_finish)
                    secondary.setText(R.string.summary_review)
                    primary.setOnClickListener { onFinish() }
                    secondary.setOnClickListener { onReview() }
                    secondary.isVisible = summary.groupSize > 0
                }
            }

            if (summary.busy) {
                primary.setText(R.string.summary_busy)
                primary.isEnabled = false
                secondary.isEnabled = false
            }
        }

        private fun bindThumbnails(items: List<MediaItem>) {
            disposeThumbs()
            binding.summaryThumbnails.removeAllViews()
            binding.summaryThumbnails.isVisible = items.isNotEmpty()
            if (items.isEmpty()) return

            val context = binding.root.context
            val thumbSize = context.dp(64)
            val thumbGap = context.dp(8)
            val corner = context.dp(12).toFloat()
            items.take(MAX_SUMMARY_THUMBS).forEach { item ->
                val thumb = ShapeableImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(thumbSize, thumbSize).apply {
                        marginEnd = thumbGap
                    }
                    shapeAppearanceModel = ShapeAppearanceModel.builder()
                        .setAllCornerSizes(corner)
                        .build()
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = item.name
                    load(item.uri) {
                        crossfade(120)
                        placeholder(R.drawable.bg_thumb_placeholder)
                        error(R.drawable.bg_thumb_error)
                        size(256, 256)
                    }
                }
                binding.summaryThumbnails.addView(thumb)
            }

            if (items.size > MAX_SUMMARY_THUMBS) {
                val more = TextView(context).apply {
                    text = context.getString(
                        R.string.summary_more,
                        items.size - MAX_SUMMARY_THUMBS
                    )
                    setTextColor(context.getColor(R.color.text_secondary))
                    textSize = 12f
                    gravity = android.view.Gravity.CENTER
                }
                binding.summaryThumbnails.addView(more)
            }
        }

        fun disposeThumbs() {
            for (index in 0 until binding.summaryThumbnails.childCount) {
                val child = binding.summaryThumbnails.getChildAt(index)
                if (child is ImageView) child.dispose()
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
