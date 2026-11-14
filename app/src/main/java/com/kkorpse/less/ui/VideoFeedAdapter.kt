package com.kkorpse.less.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import coil3.load
import coil3.request.crossfade
import coil3.request.error
import coil3.request.placeholder
import coil3.video.videoFrameMillis
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import com.kkorpse.less.R
import com.kkorpse.less.data.MediaItem
import com.kkorpse.less.databinding.ItemGroupSummaryBinding
import com.kkorpse.less.databinding.ItemVideoFeedBinding
import com.kkorpse.less.util.dp

/**
 * 视频流现在也是「一组视频 + 组尾汇总页」：视频页只负责播放和缩略图，
 * 汇总页在组尾统一确认删除。
 */
class VideoFeedAdapter(
    private val onBatchDelete: () -> Unit,
    private val onUndoBatch: () -> Unit,
    private val onNextGroup: () -> Unit,
    private val onReview: () -> Unit
) : ListAdapter<VideoPage, RecyclerView.ViewHolder>(Diff) {

    companion object {
        private const val TYPE_VIDEO = 0
        private const val TYPE_SUMMARY = 1
        private const val MAX_SUMMARY_THUMBS = 8
    }

    private val videoHolders = mutableMapOf<Int, VideoViewHolder>()

    override fun getItemViewType(position: Int): Int =
        if (getItem(position) is VideoPage.Video) TYPE_VIDEO else TYPE_SUMMARY

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_VIDEO) {
            VideoViewHolder(ItemVideoFeedBinding.inflate(inflater, parent, false))
        } else {
            SummaryViewHolder(ItemGroupSummaryBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val page = getItem(position)) {
            is VideoPage.Video -> (holder as VideoViewHolder).bind(page.item)
            is VideoPage.Summary -> (holder as SummaryViewHolder).bind(page)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is VideoViewHolder -> {
                videoHolders.entries.removeAll { it.value === holder }
                holder.release()
            }

            is SummaryViewHolder -> holder.disposeThumbs()
        }
        super.onViewRecycled(holder)
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder is VideoViewHolder) {
            videoHolders.entries.removeAll { it.value === holder }
            videoHolders[holder.bindingAdapterPosition] = holder
        }
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        if (holder is VideoViewHolder) {
            videoHolders.entries.removeAll { it.value === holder }
        }
        super.onViewDetachedFromWindow(holder)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        videoHolders.clear()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    fun playerViewAt(position: Int): PlayerView? = videoHolders[position]?.binding?.player

    fun attachPlayer(position: Int, player: androidx.media3.exoplayer.ExoPlayer?) {
        videoHolders[position]?.let { holder ->
            holder.attach(player)
        }
    }

    fun showError(position: Int, show: Boolean) {
        videoHolders[position]?.let { holder ->
            holder.binding.error.isVisible = show
            if (show) {
                holder.binding.player.visibility = View.INVISIBLE
                holder.binding.thumbnail.alpha = 1f
            }
        }
    }

    inner class VideoViewHolder(
        val binding: ItemVideoFeedBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MediaItem) {
            binding.error.isVisible = false
            binding.thumbnail.alpha = 1f
            binding.player.player = null
            binding.player.visibility = View.INVISIBLE
            binding.thumbnail.load(item.uri) {
                crossfade(120)
                placeholder(R.drawable.bg_thumb_placeholder)
                error(R.drawable.bg_thumb_error)
                size(1280, 1280)
                videoFrameMillis(1000L)
            }
        }

        fun attach(player: androidx.media3.exoplayer.ExoPlayer?) {
            binding.player.player = player
            binding.player.visibility = if (player == null) View.INVISIBLE else View.VISIBLE
            binding.thumbnail.alpha = if (player == null) 1f else 0f
        }

        fun release() {
            binding.player.player = null
            binding.thumbnail.dispose()
        }
    }

    inner class SummaryViewHolder(
        private val binding: ItemGroupSummaryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val thumbs = mutableListOf<ShapeableImageView>()

        fun bind(summary: VideoPage.Summary) {
            val deleting = summary.deleted.isNotEmpty()
            val items = if (deleting) summary.deleted else summary.pending
            val context = binding.root.context

            binding.summaryTitle.text = context.getString(
                if (deleting) R.string.summary_title_deleted else R.string.summary_title_done
            )
            binding.summarySubtitle.text = when {
                deleting -> context.getString(
                    R.string.summary_deleted_count_video,
                    summary.deleted.size
                )

                summary.pending.isNotEmpty() -> context.getString(
                    R.string.summary_pending_video,
                    summary.pending.size
                )

                else -> context.getString(R.string.summary_empty_video)
            }
            binding.summaryGroupInfo.text =
                context.getString(R.string.summary_group_info_video, summary.groupSize)

            bindThumbnails(items)

            val primary = binding.summaryPrimary
            val secondary = binding.summarySecondary
            primary.isEnabled = !summary.busy
            secondary.isEnabled = !summary.busy

            when {
                deleting -> {
                    primary.setText(R.string.summary_undo_delete)
                    secondary.setText(R.string.summary_next_group)
                    primary.setOnClickListener { onUndoBatch() }
                    secondary.setOnClickListener { onNextGroup() }
                }

                summary.pending.isNotEmpty() -> {
                    primary.text = context.getString(
                        R.string.summary_delete_n_video,
                        summary.pending.size
                    )
                    secondary.setText(R.string.summary_keep_video)
                    primary.setOnClickListener { onBatchDelete() }
                    secondary.setOnClickListener { onNextGroup() }
                }

                else -> {
                    primary.setText(R.string.summary_next_group)
                    secondary.setText(R.string.summary_review_video)
                    primary.setOnClickListener { onNextGroup() }
                    secondary.setOnClickListener { onReview() }
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
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    contentDescription = item.name
                    load(item.uri) {
                        crossfade(120)
                        placeholder(R.drawable.bg_thumb_placeholder)
                        error(R.drawable.bg_thumb_error)
                        size(256, 256)
                        videoFrameMillis(1000L)
                    }
                }
                thumbs += thumb
                binding.summaryThumbnails.addView(thumb)
            }

            if (items.size > MAX_SUMMARY_THUMBS) {
                val more = android.widget.TextView(context).apply {
                    text = context.getString(
                        R.string.summary_more,
                        items.size - MAX_SUMMARY_THUMBS
                    )
                    setTextColor(context.getColor(R.color.text_secondary))
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        thumbSize
                    ).apply { marginEnd = thumbGap }
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                binding.summaryThumbnails.addView(more)
            }
        }

        fun disposeThumbs() {
            thumbs.forEach { it.dispose() }
            thumbs.clear()
        }
    }

    private object Diff : DiffUtil.ItemCallback<VideoPage>() {
        override fun areItemsTheSame(oldItem: VideoPage, newItem: VideoPage): Boolean {
            return when {
                oldItem is VideoPage.Video && newItem is VideoPage.Video ->
                    oldItem.item.uri == newItem.item.uri

                oldItem is VideoPage.Summary && newItem is VideoPage.Summary -> true
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: VideoPage, newItem: VideoPage): Boolean =
            oldItem == newItem
    }
}
