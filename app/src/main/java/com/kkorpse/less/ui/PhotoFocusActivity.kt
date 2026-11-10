package com.kkorpse.less.ui

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.kkorpse.less.LessApp
import com.kkorpse.less.R
import com.kkorpse.less.data.MediaItem
import com.kkorpse.less.data.PhotoFocusSession
import com.kkorpse.less.databinding.ActivityPhotoFocusBinding
import com.kkorpse.less.util.Format
import com.kkorpse.less.util.dp
import kotlinx.coroutines.launch

class PhotoFocusActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoFocusBinding
    private val viewModel: PhotoFocusViewModel by viewModels()

    private lateinit var adapter: PhotoFocusAdapter
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var trashController: TrashController

    private var initialScrollDone = false
    private var pendingScrollIndex: Int? = null

    private val swipePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardInset by lazy { dp(10).toFloat() }
    private val cardRadius by lazy { dp(24).toFloat() }
    private val trashIcon: Drawable? by lazy {
        ContextCompat.getDrawable(this, R.drawable.ic_delete)?.mutate()?.also {
            DrawableCompat.setTint(it, Color.WHITE)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val session = PhotoFocusSession
        if (session.items.isEmpty()) {
            finish()
            return
        }

        binding = ActivityPhotoFocusBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel.initialize(
            session.items,
            session.startIndex,
            session.canTrash,
            session.autoAppend
        )
        val app = application as LessApp
        adapter = PhotoFocusAdapter(app.preferences.doubleTapAction) { item ->
            toggleFavorite(item)
        }

        layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.pager.layoutManager = layoutManager
        binding.pager.adapter = adapter
        binding.pager.itemAnimator = null
        binding.pager.setHasFixedSize(true)
        binding.pager.setItemViewCacheSize(2)
        PagerSnapHelper().attachToRecyclerView(binding.pager)

        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setupInsets()
        setupSwipe()
        setupActions()

        trashController = TrashController(this) { request, success ->
            handleTrashResult(request, success)
        }

        observeItems()
        updateOverlay()
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            (binding.back.layoutParams as FrameLayout.LayoutParams).topMargin =
                dp(12) + bars.top
            (binding.counter.layoutParams as FrameLayout.LayoutParams).topMargin =
                dp(18) + bars.top
            (binding.bottomBar.layoutParams as FrameLayout.LayoutParams).bottomMargin =
                bars.bottom
            insets
        }
    }

    private fun setupActions() {
        binding.back.setOnClickListener { finish() }
        binding.favoriteButton.setOnClickListener {
            currentItem()?.let { toggleFavorite(it) }
        }
        binding.undoButton.setOnClickListener { undoLast() }
        binding.pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    updateCurrent()
                }
            }
        })
    }

    private fun setupSwipe() {
        val callback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.UP) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.28f

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return
                val item = adapter.currentList.getOrNull(position) ?: return
                if (!viewModel.canTrash) {
                    adapter.notifyItemChanged(position)
                    return
                }
                if (trashController.isBusy) {
                    adapter.notifyItemChanged(position)
                    return
                }

                // 先让卡片回到原位，系统确认框结束后再决定移除还是保留。
                binding.pager.post {
                    if (position < adapter.itemCount) adapter.notifyItemChanged(position)
                }
                trashController.request(item, trash = true, index = position)
                refreshUndo()
            }

            override fun onChildDraw(
                canvas: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                if (dY < 0f && isCurrentlyActive) {
                    val itemView = viewHolder.itemView
                    val rect = RectF(
                        itemView.left + cardInset,
                        itemView.top + cardInset,
                        itemView.right - cardInset,
                        itemView.bottom - cardInset
                    )
                    val progress = (-dY / itemView.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    swipePaint.color = Color.argb(
                        (80 + 120 * progress).toInt(),
                        255,
                        90,
                        95
                    )
                    canvas.drawRoundRect(rect, cardRadius, cardRadius, swipePaint)

                    trashIcon?.let { icon ->
                        val size = dp(52)
                        val cx = rect.centerX()
                        val cy = rect.centerY()
                        icon.setBounds(
                            (cx - size / 2).toInt(),
                            (cy - size / 2).toInt(),
                            (cx + size / 2).toInt(),
                            (cy + size / 2).toInt()
                        )
                        icon.alpha = (255 * progress).toInt()
                        icon.draw(canvas)
                    }
                }
                super.onChildDraw(
                    canvas,
                    recyclerView,
                    viewHolder,
                    dX,
                    dY,
                    actionState,
                    isCurrentlyActive
                )
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.pager)
    }

    private fun observeItems() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.items.collect { items ->
                    adapter.submitList(items) {
                        if (!initialScrollDone) {
                            initialScrollDone = true
                            scrollTo(viewModel.currentIndex)
                        }
                        pendingScrollIndex?.let { index ->
                            if (items.isNotEmpty()) {
                                pendingScrollIndex = null
                                scrollTo(index)
                            }
                        }
                        binding.emptyMessage.isVisible = items.isEmpty()
                        updateOverlay()
                    }
                }
            }
        }
    }

    private fun handleTrashResult(request: TrashController.Request, success: Boolean) {
        if (request.trash) {
            if (success) {
                pendingScrollIndex = request.index
                viewModel.confirmTrash(request.item, request.index)
                Toast.makeText(this, R.string.trash_success, Toast.LENGTH_SHORT).show()
                viewModel.appendIfNeeded(request.index)
            } else {
                binding.pager.post {
                    if (request.index < adapter.itemCount) {
                        adapter.notifyItemChanged(request.index)
                    }
                }
            }
        } else {
            if (success) {
                pendingScrollIndex = request.index
                viewModel.confirmRestore(request.item, request.index)
                Toast.makeText(this, R.string.restore_success, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.restore_failed, Toast.LENGTH_SHORT).show()
            }
        }
        refreshUndo()
    }

    private fun undoLast() {
        val entry = viewModel.peekUndo() ?: return
        if (trashController.isBusy) return
        trashController.request(entry.item, trash = false, index = entry.index)
        refreshUndo()
    }

    private fun toggleFavorite(item: MediaItem) {
        val app = application as LessApp
        val favorite = app.browseStore.toggleFavorite(item.uri)
        Toast.makeText(
            this,
            if (favorite) R.string.favorite_added else R.string.favorite_removed,
            Toast.LENGTH_SHORT
        ).show()
        if (currentItem()?.uri == item.uri) {
            updateFavorite(item)
        }
    }

    private fun scrollTo(index: Int) {
        if (adapter.itemCount == 0) return
        val position = index.coerceIn(0, adapter.itemCount - 1)
        layoutManager.scrollToPositionWithOffset(position, 0)
        viewModel.currentIndex = position
        binding.pager.post { updateCurrent() }
    }

    private fun currentPosition(): Int {
        val completely = layoutManager.findFirstCompletelyVisibleItemPosition()
        if (completely != RecyclerView.NO_POSITION) return completely
        return layoutManager.findFirstVisibleItemPosition()
    }

    private fun currentItem(): MediaItem? {
        val position = currentPosition()
        if (position == RecyclerView.NO_POSITION) return null
        return adapter.currentList.getOrNull(position)
    }

    private fun updateCurrent() {
        val position = currentPosition()
        if (position == RecyclerView.NO_POSITION) {
            updateOverlay()
            return
        }
        viewModel.currentIndex = position
        val item = adapter.currentList.getOrNull(position)
        if (item != null) {
            (application as LessApp).browseStore.markViewed(item.uri, false)
            updateOverlay(item)
            viewModel.appendIfNeeded(position)
        }
    }

    private fun updateOverlay(item: MediaItem? = currentItem()) {
        if (item == null) {
            binding.counter.text = ""
            binding.timeLabel.isVisible = false
            binding.favoriteButton.isEnabled = false
            binding.favoriteButton.alpha = 0.4f
            refreshUndo()
            return
        }

        binding.counter.text = getString(
            R.string.viewer_position,
            viewModel.currentIndex + 1,
            adapter.itemCount
        )
        binding.timeLabel.isVisible = true
        binding.timeLabel.text = Format.relativeTime(item.dateMillis)
        binding.favoriteButton.isEnabled = true
        binding.favoriteButton.alpha = 1f
        updateFavorite(item)
        refreshUndo()
    }

    private fun updateFavorite(item: MediaItem) {
        val app = application as LessApp
        val favorite = app.browseStore.isFavorite(item.uri)
        binding.favoriteIcon.setImageResource(
            if (favorite) R.drawable.ic_heart_filled else R.drawable.ic_heart
        )
        binding.favoriteIcon.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (favorite) R.color.heart else R.color.accent)
        )
        binding.favoriteLabel.text = getString(
            if (favorite) R.string.favorited else R.string.favorite
        )
    }

    private fun refreshUndo() {
        val enabled = viewModel.canUndo.value && !trashController.isBusy
        binding.undoButton.isEnabled = enabled
        binding.undoButton.alpha = if (enabled) 1f else 0.4f
    }
}
