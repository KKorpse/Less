package com.kkorpse.less.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.kkorpse.less.data.MediaItem
import com.kkorpse.less.databinding.ItemVideoFeedBinding

class VideoFeedAdapter : ListAdapter<MediaItem, VideoFeedAdapter.VideoViewHolder>(Diff) {

    private val holders = mutableMapOf<Int, VideoViewHolder>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        return VideoViewHolder(
            ItemVideoFeedBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holders.entries.removeAll { it.value === holder }
        holders[position] = holder
        holder.bind(getItem(position), position)
    }

    override fun onViewRecycled(holder: VideoViewHolder) {
        holders.entries.removeAll { it.value === holder }
        holder.release()
        super.onViewRecycled(holder)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        holders.clear()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    fun playerViewAt(position: Int): PlayerView? = holders[position]?.binding?.player

    fun attachPlayer(position: Int, player: androidx.media3.exoplayer.ExoPlayer?) {
        holders[position]?.let { holder ->
            holder.binding.player.player = player
            holder.binding.player.visibility = if (player == null) View.INVISIBLE else View.VISIBLE
            holder.binding.thumbnail.alpha = if (player == null) 1f else 0f
        }
    }

    fun showError(position: Int, show: Boolean) {
        holders[position]?.let { holder ->
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

        fun bind(item: MediaItem, position: Int) {
            binding.error.isVisible = false
            binding.thumbnail.alpha = 1f
            binding.player.player = null
            binding.player.visibility = View.INVISIBLE
            binding.thumbnail.load(item.uri) {
                crossfade(120)
                placeholder(com.kkorpse.less.R.drawable.bg_thumb_placeholder)
                error(com.kkorpse.less.R.drawable.bg_thumb_error)
                size(1280, 1280)
                videoFrameMillis(1000L)
            }
        }

        fun release() {
            binding.player.player = null
            binding.thumbnail.dispose()
        }
    }

    private object Diff : DiffUtil.ItemCallback<MediaItem>() {
        override fun areItemsTheSame(oldItem: MediaItem, newItem: MediaItem): Boolean =
            oldItem.uri == newItem.uri

        override fun areContentsTheSame(oldItem: MediaItem, newItem: MediaItem): Boolean =
            oldItem == newItem
    }
}
