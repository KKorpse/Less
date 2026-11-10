package com.kkorpse.less.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import coil3.load
import coil3.request.crossfade
import com.kkorpse.less.data.MediaItem
import com.kkorpse.less.databinding.ViewerPageImageBinding
import com.kkorpse.less.databinding.ViewerPageVideoBinding

@androidx.annotation.OptIn(UnstableApi::class)
class ViewerAdapter(
    private val items: List<MediaItem>,
    private val onImageTap: () -> Unit,
    private val onVideoControllerVisibilityChanged: (Int, Boolean) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_IMAGE = 0
        private const val TYPE_VIDEO = 1
    }

    var currentPosition: Int = 0

    private val videoHolders = linkedSetOf<VideoViewHolder>()

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int =
        if (items[position].isVideo) TYPE_VIDEO else TYPE_IMAGE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_VIDEO) {
            VideoViewHolder(
                ViewerPageVideoBinding.inflate(inflater, parent, false)
            ).also { videoHolders += it }
        } else {
            ImageViewHolder(
                ViewerPageImageBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is ImageViewHolder -> holder.bind(item)
            is VideoViewHolder -> holder.bind(item, position)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is VideoViewHolder -> {
                videoHolders -= holder
                holder.releasePlayer()
            }

            is ImageViewHolder -> holder.dispose()
        }
        super.onViewRecycled(holder)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        releaseAll()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    fun pauseAll() {
        videoHolders.forEach { it.player?.pause() }
    }

    fun releaseAll() {
        videoHolders.toList().forEach {
            it.releasePlayer()
        }
        videoHolders.clear()
    }

    fun showController(position: Int) {
        videoHolders.firstOrNull { it.bindingAdapterPosition == position }
            ?.showControllerIfCurrent(position)
    }

    fun hideControllers() {
        videoHolders.forEach { it.binding.player.hideController() }
    }

    private inner class ImageViewHolder(
        private val binding: ViewerPageImageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.photo.setOnViewTapListener { _, _, _ -> onImageTap() }
        }

        fun dispose() {
            binding.photo.dispose()
        }

        fun bind(item: MediaItem) {
            binding.progress.isVisible = true
            binding.error.isVisible = false
            binding.photo.contentDescription = item.name
            binding.photo.setScale(1f, false)
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
    }

    private inner class VideoViewHolder(
        val binding: ViewerPageVideoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        var player: ExoPlayer? = null
            private set

        fun bind(item: MediaItem, position: Int) {
            releasePlayer()
            binding.error.isVisible = false
            binding.player.isVisible = true

            val exoPlayer = ExoPlayer.Builder(binding.root.context).build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true
                )
                setMediaItem(androidx.media3.common.MediaItem.fromUri(item.uri))
                prepare()
                playWhenReady = false
            }
            player = exoPlayer

            binding.player.player = exoPlayer
            binding.player.setControllerVisibilityListener(
                PlayerView.ControllerVisibilityListener { visibility ->
                    val adapterPosition = bindingAdapterPosition
                    if (adapterPosition != RecyclerView.NO_POSITION) {
                        onVideoControllerVisibilityChanged(
                            adapterPosition,
                            visibility == View.VISIBLE
                        )
                    }
                }
            )
            exoPlayer.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    binding.error.isVisible = true
                }
            })

            binding.player.post {
                if (player != null && bindingAdapterPosition == currentPosition) {
                    binding.player.showController()
                }
            }
        }

        fun showControllerIfCurrent(position: Int) {
            if (player != null && bindingAdapterPosition == position) {
                binding.player.showController()
            }
        }

        fun releasePlayer() {
            binding.player.player = null
            player?.release()
            player = null
        }
    }
}
