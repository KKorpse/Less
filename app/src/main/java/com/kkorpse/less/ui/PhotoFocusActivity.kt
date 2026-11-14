package com.kkorpse.less.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kkorpse.less.LessApp
import com.kkorpse.less.R
import com.kkorpse.less.data.MediaItem
import com.kkorpse.less.data.PhotoFocusSession
import com.kkorpse.less.databinding.ActivityPhotoFocusBinding
import com.kkorpse.less.util.Format
import com.kkorpse.less.util.PhotoColorResolver
import com.kkorpse.less.util.PhotoLocationResolver
import com.kkorpse.less.util.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PhotoFocusActivity : AppCompatActivity() {

    companion object {
        /** 上滑到卡片高度的 8% 就算要删除，手指不松开时表现为顶部红光逐渐亮起。 */
        private const val SWIPE_THRESHOLD = 0.08f
    }

    private lateinit var binding: ActivityPhotoFocusBinding
    private val viewModel: PhotoFocusViewModel by viewModels()

    private lateinit var adapter: PhotoFocusAdapter
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var batchController: BatchTrashController
    private var lastDeleteItems: List<MediaItem> = emptyList()
    private var exitingView: View? = null
    private lateinit var favoriteController: FavoriteController
    private val locationResolver = PhotoLocationResolver()
    private val colorResolver = PhotoColorResolver()

    private var initialScrollDone = false
    private var lastGeneration = 0
    private var pendingScrollIndex: Int? = null
    private var pendingScrollOriginalIndex: Int? = null
    private var locationJob: Job? = null
    private var colorJob: Job? = null

    private val locationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        updateCurrent()
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

        viewModel.initialize(session.items, session.startIndex, session.autoNextGroup)
        val app = application as LessApp
        adapter = PhotoFocusAdapter(
            doubleTapAction = app.preferences.doubleTapAction,
            onFavorite = ::toggleFavorite,
            onTogglePending = { entry -> viewModel.togglePendingSelection(entry.item) },
            onConfirmDelete = ::requestBatchDelete,
            onDiscardGroup = ::discardGroup
        )

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
        setupActions()
        setupSwipe()

        batchController = BatchTrashController(this, this) { trash, success ->
            handleBatchResult(trash, success)
        }
        favoriteController = FavoriteController(this, this) { request, success ->
            handleFavoriteResult(request, success)
        }

        observePages()
        updateCurrent()
        maybeRequestLocationPermission()
    }

    override fun onDestroy() {
        locationJob?.cancel()
        colorJob?.cancel()
        super.onDestroy()
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            (binding.back.layoutParams as FrameLayout.LayoutParams).topMargin =
                dp(12) + bars.top
            (binding.bottomBar.layoutParams as FrameLayout.LayoutParams).bottomMargin =
                bars.bottom
            val contentTop = bars.top + dp(8)
            val contentBottom = bars.bottom + dp(72)
            binding.pager.post { adapter.setContentInsets(contentTop, contentBottom) }
            insets
        }
    }

    private fun setupActions() {
        binding.back.setOnClickListener { finish() }
        binding.favoriteButton.setOnClickListener {
            currentPhoto()?.let { toggleFavorite(it) }
        }
        binding.undoButton.setOnClickListener { undoLastMark() }
        binding.infoButton.setOnClickListener { showPhotoInfo() }
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
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                val page = adapter.currentList.getOrNull(viewHolder.bindingAdapterPosition)
                return if (page is FocusPage.Photo) {
                    makeMovementFlags(0, ItemTouchHelper.UP)
                } else {
                    0
                }
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float =
                SWIPE_THRESHOLD

            override fun getSwipeEscapeVelocity(defaultValue: Float): Float =
                defaultValue.coerceAtMost(600f)

            override fun onChildDraw(
                canvas: android.graphics.Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val card = viewHolder.itemView.findViewById<FocusCardLayout>(R.id.card)
                if (card != null) {
                    if (isCurrentlyActive && dY < 0f) {
                        // 以「触发删除的距离」为 1，红光与缩小同步渐变。
                        val progress = (-dY / (viewHolder.itemView.height * SWIPE_THRESHOLD))
                            .coerceIn(0f, 1f)
                        card.scaleX = 1f - 0.10f * progress
                        card.scaleY = 1f - 0.10f * progress
                        card.alpha = 1f - 0.45f * progress
                        binding.swipeGlow.alpha = progress * 0.9f
                    } else {
                        card.scaleX = 1f
                        card.scaleY = 1f
                        card.alpha = 1f
                        if (!isCurrentlyActive) binding.swipeGlow.alpha = 0f
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

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                val page = adapter.currentList.getOrNull(position) as? FocusPage.Photo ?: return
                val view = viewHolder.itemView
                exitingView = view
                binding.swipeGlow.animate().alpha(0f).setDuration(120).start()
                // 先播放缩小上移的退场动画，再从列表里移除，避免卡片“啪”一下消失。
                view.post {
                    view.animate()
                        .translationY(-view.height * 0.28f)
                        .scaleX(0.86f)
                        .scaleY(0.86f)
                        .alpha(0f)
                        .setDuration(180L)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction {
                            if (exitingView === view) exitingView = null
                            markForDeletion(position, page)
                            // 复位位移与缩放，避免复用时位置错乱；alpha 保持 0，
                            // 由 bind() 在下一张绑定时还原，避免删除后闪一下原图。
                            view.translationY = 0f
                            view.translationX = 0f
                            view.scaleX = 1f
                            view.scaleY = 1f
                        }
                        .start()
                }
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ) {
                binding.swipeGlow.alpha = 0f
                // 正在播放退场动画的卡片不要被 ItemTouchHelper 重置回原状。
                if (viewHolder.itemView === exitingView) return
                viewHolder.itemView.findViewById<FocusCardLayout>(R.id.card)?.let {
                    it.scaleX = 1f
                    it.scaleY = 1f
                    it.alpha = 1f
                }
                super.clearView(recyclerView, viewHolder)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.pager)
    }

    private fun observePages() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.pages.collect { pages ->
                    adapter.submitList(pages) {
                        if (!initialScrollDone) {
                            initialScrollDone = true
                            lastGeneration = viewModel.groupGeneration
                            scrollTo(viewModel.currentIndex)
                        }
                        if (viewModel.groupGeneration != lastGeneration) {
                            lastGeneration = viewModel.groupGeneration
                            scrollTo(0)
                        }
                        pendingScrollIndex?.let { index ->
                            if (pages.isNotEmpty()) {
                                pendingScrollIndex = null
                                scrollTo(index)
                            }
                        }
                        pendingScrollOriginalIndex?.let { originalIndex ->
                            val index = pages.indexOfFirst {
                                it is FocusPage.Photo && it.originalIndex == originalIndex
                            }
                            if (index >= 0) {
                                pendingScrollOriginalIndex = null
                                scrollTo(index)
                            }
                        }
                        updateCurrent()
                    }
                }
            }
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

    private fun currentPage(): FocusPage? {
        val position = currentPosition()
        if (position == RecyclerView.NO_POSITION) return null
        return adapter.currentList.getOrNull(position)
    }

    private fun currentPhoto(): MediaItem? = (currentPage() as? FocusPage.Photo)?.item

    private fun updateCurrent() {
        val position = currentPosition()
        if (position == RecyclerView.NO_POSITION) {
            updateChrome(null)
            return
        }
        viewModel.currentIndex = position
        val page = adapter.currentList.getOrNull(position)
        updateChrome(page)
        if (page is FocusPage.Photo) {
            (application as LessApp).browseStore.markViewed(page.item.uri, false)
        }
    }

    private fun updateChrome(page: FocusPage?) {
        when (page) {
            is FocusPage.Photo -> {
                binding.bottomBar.isVisible = true
                binding.favoriteButton.isEnabled = true
                binding.favoriteButton.alpha = 1f
                updateFavorite(page.item)
                refreshUndo()
                updateInfo(page)
                updatePageBackground(page)
            }

            is FocusPage.Summary -> {
                locationJob?.cancel()
                binding.bottomBar.isVisible = false
                if (page.pending.isEmpty() && !page.busy && viewModel.autoNextGroup) {
                    viewModel.loadNextGroup()
                }
            }

            null -> {
                locationJob?.cancel()
                binding.bottomBar.isVisible = false
            }
        }
    }

    private fun updatePageBackground(page: FocusPage.Photo) {
        colorJob?.cancel()
        colorJob = lifecycleScope.launch {
            val color = colorResolver.resolve(this@PhotoFocusActivity, page.item.uri)
            val current = currentPage() as? FocusPage.Photo
            if (current?.item?.uri != page.item.uri) return@launch
            val gradient = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(colorResolver.topColor(color), colorResolver.pageColor(color))
            )
            binding.root.background = gradient
        }
    }

    private fun updateInfo(page: FocusPage.Photo) {
        binding.infoTitle.text = Format.relativeTime(page.item.dateMillis)
        binding.infoAddress.text = getString(R.string.location_loading)

        locationJob?.cancel()
        locationJob = lifecycleScope.launch {
            val address = locationResolver.resolve(this@PhotoFocusActivity, page.item.uri)
            val current = currentPage() as? FocusPage.Photo
            if (current?.item?.uri == page.item.uri) {
                binding.infoAddress.text = address
            }
        }
    }

    private fun showPhotoInfo() {
        val page = currentPage() as? FocusPage.Photo ?: return
        val message = buildString {
            append(page.item.name)
            append("\n")
            append(Format.fullDate(page.item.dateMillis))
            append("\n")
            append(binding.infoAddress.text)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.photo_info_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun maybeRequestLocationPermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_MEDIA_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val app = application as LessApp
        if (app.preferences.locationAsked) return
        app.preferences.locationAsked = true

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.location_permission_title)
            .setMessage(R.string.location_permission_message)
            .setPositiveButton(R.string.location_permission_allow) { _, _ ->
                locationLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
            }
            .setNegativeButton(R.string.location_permission_not_now, null)
            .show()
    }

    private fun toggleFavorite(item: MediaItem) {
        val store = (application as LessApp).browseStore
        val favorite = !store.isFavorite(item.uri)
        // 先更新本地镜像和 UI，再尝试写入系统媒体库；系统写入失败时回滚。
        store.setFavorite(item.uri, favorite)
        if (currentPhoto()?.uri == item.uri) {
            updateFavorite(item)
        }
        if (!favoriteController.request(item, favorite)) {
            store.markLocalOnly(item.uri, favorite)
        }
    }

    private fun handleFavoriteResult(request: FavoriteController.Request, success: Boolean) {
        if (success) return
        val store = (application as LessApp).browseStore
        request.items.forEach { store.setFavorite(it.uri, !request.favorite) }
        currentPhoto()?.let { updateFavorite(it) }
        Toast.makeText(this, R.string.favorite_sync_failed, Toast.LENGTH_SHORT).show()
    }

    private fun updateFavorite(item: MediaItem) {
        val favorite = (application as LessApp).browseStore.isFavorite(item.uri)
        binding.favoriteButton.setImageResource(
            if (favorite) R.drawable.ic_heart_filled else R.drawable.ic_heart
        )
        binding.favoriteButton.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (favorite) R.color.heart else R.color.accent)
        )
        binding.favoriteButton.contentDescription = getString(
            if (favorite) R.string.unfavorite_content_desc else R.string.favorite_content_desc
        )
    }

    private fun markForDeletion(position: Int, page: FocusPage.Photo) {
        if (batchController.isBusy) return
        pendingScrollIndex = position
        if (viewModel.markForDeletion(page)) {
            binding.pager.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        } else {
            pendingScrollIndex = null
            binding.pager.post {
                if (position < adapter.itemCount) adapter.notifyItemChanged(position)
            }
            Toast.makeText(this, R.string.summary_finish_first, Toast.LENGTH_SHORT).show()
        }
    }

    private fun undoLastMark() {
        if (batchController.isBusy) return
        val entry = viewModel.undoLastPending() ?: return
        pendingScrollOriginalIndex = entry.originalIndex
    }

    private fun refreshUndo() {
        val enabled = viewModel.pendingEntries().isNotEmpty() && !batchController.isBusy
        binding.undoButton.isEnabled = enabled
        binding.undoButton.alpha = if (enabled) 1f else 0.4f
    }

    private fun finishGroup() {
        if (viewModel.autoNextGroup) {
            viewModel.loadNextGroup()
        } else {
            finish()
        }
    }

    private fun requestBatchDelete() {
        val selected = viewModel.selectedEntries()
        if (selected.isEmpty() || batchController.isBusy) return
        lastDeleteItems = selected.map { it.item }
        viewModel.setBatchBusy(true)
        batchController.request(lastDeleteItems, trash = true)
        refreshUndo()
    }

    /** 放弃这一组的删除标记，直接进入下一组。 */
    private fun discardGroup() {
        viewModel.discardGroup()
        advanceAfterGroup()
    }

    private fun advanceAfterGroup() {
        if (viewModel.autoNextGroup) {
            viewModel.loadNextGroup()
        } else {
            finish()
        }
    }

    private fun handleBatchResult(trash: Boolean, success: Boolean) {
        if (!success) {
            viewModel.setBatchBusy(false)
            refreshUndo()
            return
        }
        if (trash) {
            viewModel.completeBatchDelete(lastDeleteItems)
            lastDeleteItems = emptyList()
            Toast.makeText(this, R.string.batch_trash_success, Toast.LENGTH_SHORT).show()
            // 删除完成直接进下一组，不再让用户二次确认。
            advanceAfterGroup()
        }
        refreshUndo()
    }

}
