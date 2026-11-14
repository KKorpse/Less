package com.kkorpse.less.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import coil3.load
import coil3.request.crossfade
import coil3.request.error
import coil3.request.placeholder
import com.kkorpse.less.R
import com.kkorpse.less.databinding.ItemReviewCellBinding

/**
 * 汇总卡片里的九宫格：默认全部勾选，点一下取消。
 */
class ReviewGridAdapter(
    private val onToggle: (PendingDelete) -> Unit
) : ListAdapter<PendingDelete, ReviewGridAdapter.CellHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CellHolder {
        return CellHolder(
            ItemReviewCellBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindViewHolder(holder: CellHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CellHolder(
        private val binding: ItemReviewCellBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: PendingDelete) {
            binding.reviewThumb.setShapeAppearanceModel(
                com.google.android.material.shape.ShapeAppearanceModel.builder()
                    .setAllCornerSizes(binding.root.resources.displayMetrics.density * 12f)
                    .build()
            )
            binding.reviewThumb.load(entry.item.uri) {
                crossfade(120)
                placeholder(R.drawable.bg_thumb_placeholder)
                error(R.drawable.bg_thumb_error)
                size(320, 320)
            }
            binding.reviewThumb.alpha = if (entry.selected) 1f else 0.35f
            binding.reviewCheck.visibility =
                if (entry.selected) android.view.View.VISIBLE else android.view.View.INVISIBLE
            binding.root.setOnClickListener { onToggle(entry) }
        }

        fun dispose() {
            binding.reviewThumb.dispose()
        }
    }

    private object Diff : DiffUtil.ItemCallback<PendingDelete>() {
        override fun areItemsTheSame(oldItem: PendingDelete, newItem: PendingDelete): Boolean =
            oldItem.item.uri == newItem.item.uri

        override fun areContentsTheSame(oldItem: PendingDelete, newItem: PendingDelete): Boolean =
            oldItem == newItem
    }
}
