package com.kkorpse.less.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import coil3.load
import coil3.request.crossfade
import coil3.request.error
import coil3.request.placeholder
import coil3.video.videoFrameMillis
import com.kkorpse.less.R
import com.kkorpse.less.data.MediaItem
import com.kkorpse.less.databinding.ItemDateHeaderBinding
import com.kkorpse.less.databinding.ItemMediaBinding
import com.kkorpse.less.util.Format
import java.time.LocalDate

sealed class GalleryEntry {
    data class Header(
        val date: LocalDate,
        val label: String,
        val count: Int
    ) : GalleryEntry()

    data class Item(val media: MediaItem) : GalleryEntry()
}

class GalleryAdapter(
    private val onClick: (MediaItem) -> Unit
) : ListAdapter<GalleryEntry, RecyclerView.ViewHolder>(Diff) {

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_MEDIA = 1
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is GalleryEntry.Header -> TYPE_HEADER
        is GalleryEntry.Item -> TYPE_MEDIA
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(
                ItemDateHeaderBinding.inflate(inflater, parent, false)
            )

            else -> MediaViewHolder(
                ItemMediaBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val entry = getItem(position)) {
            is GalleryEntry.Header -> (holder as HeaderViewHolder).bind(entry)
            is GalleryEntry.Item -> (holder as MediaViewHolder).bind(entry.media)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is MediaViewHolder) {
            holder.dispose()
        }
        super.onViewRecycled(holder)
    }

    private inner class HeaderViewHolder(
        private val binding: ItemDateHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(header: GalleryEntry.Header) {
            binding.dateLabel.text = header.label
            binding.dateCount.text = Format.count(header.count)
        }
    }

    private inner class MediaViewHolder(
        private val binding: ItemMediaBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MediaItem) {
            val context = binding.root.context
            binding.thumb.load(item.uri) {
                crossfade(120)
                placeholder(R.drawable.bg_thumb_placeholder)
                error(R.drawable.bg_thumb_error)
                size(640, 640)
                if (item.isVideo) {
                    videoFrameMillis(1000L)
                }
            }

            binding.videoPlay.isVisible = item.isVideo
            binding.videoDuration.isVisible = item.isVideo && item.durationMs > 0L
            if (item.durationMs > 0L) {
                binding.videoDuration.text = Format.duration(item.durationMs)
            }

            binding.root.contentDescription = context.getString(
                if (item.isVideo) R.string.video_desc else R.string.image_desc,
                item.name
            )
            binding.root.setOnClickListener { onClick(item) }
        }

        fun dispose() {
            binding.thumb.dispose()
        }
    }

    private object Diff : DiffUtil.ItemCallback<GalleryEntry>() {
        override fun areItemsTheSame(oldItem: GalleryEntry, newItem: GalleryEntry): Boolean {
            return when {
                oldItem is GalleryEntry.Header && newItem is GalleryEntry.Header ->
                    oldItem.date == newItem.date

                oldItem is GalleryEntry.Item && newItem is GalleryEntry.Item ->
                    oldItem.media.uri == newItem.media.uri

                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: GalleryEntry, newItem: GalleryEntry): Boolean {
            return oldItem == newItem
        }
    }
}
