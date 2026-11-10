package com.kkorpse.less.ui

import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import coil3.load
import coil3.request.crossfade
import com.kkorpse.less.data.MediaItem
import com.kkorpse.less.databinding.ItemPhotoFocusBinding
import com.kkorpse.less.prefs.DoubleTapAction

class PhotoFocusAdapter(
    private val doubleTapAction: DoubleTapAction,
    private val onFavorite: (MediaItem) -> Unit
) : ListAdapter<MediaItem, PhotoFocusAdapter.PhotoViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        return PhotoViewHolder(
            ItemPhotoFocusBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: PhotoViewHolder) {
        holder.dispose()
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
            binding.progress.isVisible = true
            binding.error.isVisible = false
            binding.photo.contentDescription = item.name
            binding.photo.setScale(1f, false)
            binding.photo.setZoomable(true)
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

    private object Diff : DiffUtil.ItemCallback<MediaItem>() {
        override fun areItemsTheSame(oldItem: MediaItem, newItem: MediaItem): Boolean =
            oldItem.uri == newItem.uri

        override fun areContentsTheSame(oldItem: MediaItem, newItem: MediaItem): Boolean =
            oldItem == newItem
    }
}
