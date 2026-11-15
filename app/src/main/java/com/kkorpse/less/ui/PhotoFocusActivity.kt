package com.kkorpse.less.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
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
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
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
    private var lastCleanupUris: List<Uri> = emptyList()
    private var pendingNextReset: View? = null
    private var exitingView: View? = null
    private var swipeOffsetY = 0f
    private var swipingView: View? = null
    private var lastPreloadPosition = -1
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
        binding.pager.setItemViewCacheSize(3)
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
        favoriteController = FavoriteController(this) { request, success ->
            handleFavoriteResult(request, success)
        }

        observePages()
        updateCurrent()
        maybeRequestLocationPermission()
    }

    override fun onPause() {
        super.onPause()
        // 只记录从首页进入的组；收藏页进来的会话不覆盖首页进度。
        if (!PhotoFocusSession.autoNextGroup) return
        val app = application as LessApp
        val mode = app.preferences.browseMode.name
        app.browseStore.setGroupIndex(mode, viewModel.currentIndex)
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

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                // 当前这张正常向左划走；后面的牌贴到中间，从下方浮起盖上来。
                val width = recyclerView.width.toFloat().coerceAtLeast(1f)
                val center = width / 2f
                for (index in 0 until recyclerView.childCount) {
                    val child = recyclerView.getChildAt(index)
                    if (child === exitingView || child === swipingView) {
                        continue
                    }
                    val card = child.findViewById<FocusCardLayout>(R.id.card) ?: continue
                    val childCenter = (child.left + child.right) / 2f
                    val offset = ((childCenter - center) / width).coerceIn(-1f, 1f)
                    if (offset > 0f) {
                        // 后面还没到中心的牌：不再从右边滑，改为从下方浮起
                        child.translationX = -offset * width
                        child.translationZ = 0f
                        card.alpha = (1f - offset).coerceIn(0f, 1f)
                        card.translationY = dp(72).toFloat() * offset
                        val scale = 1f - 0.06f * offset
                        card.scaleX = scale
                        card.scaleY = scale
                    } else {
                        // 当前这张：保持原来的横向位置，随手指向左划走
                        child.translationX = 0f
                        child.translationZ = 1f
                        card.alpha = 1f
                        card.translationY = 0f
                        card.scaleX = 1f
                        card.scaleY = 1f
                    }
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
                val itemView = viewHolder.itemView
                // 已经在播退场动画的卡片，不要再被重置。
                if (itemView === exitingView) return
                val card = itemView.findViewById<FocusCardLayout>(R.id.card)
                if (card != null) {
                    if (isCurrentlyActive && dY < 0f) {
                        swipingView = itemView
                        swipeOffsetY = dY
                        // 以「触发删除的距离」为 1，红光与缩小同步渐变。
                        val progress = (-dY / (itemView.height * SWIPE_THRESHOLD))
                            .coerceIn(0f, 1f)
                        card.scaleX = 1f - 0.10f * progress
                        card.scaleY = 1f - 0.10f * progress
                        card.alpha = 1f - 0.45f * progress
                        binding.swipeGlow.alpha = progress * 0.9f
                    } else if (itemView !== swipingView) {
                        // 只有真正结束了手势（clearView 会清空 swipingView）才复位，
                        // 否则松手这一帧会把卡片弹回 1x，看起来就是抖动放大。
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
                val itemView = viewHolder.itemView
                val card = itemView.findViewById<FocusCardLayout>(R.id.card)
                exitingView = itemView
                swipingView = null
                // 当前这张要在上面，滑走时才会露出下面那张，而不是被它盖住。
                itemView.translationZ = 2f
                binding.swipeGlow.animate().alpha(0f).setDuration(240L).start()

                if (card == null) {
                    markForDeletion(position, page)
                    return
                }

                // 下一张：和左滑完全一样的「发牌」参数，从下方浮起。
                val nextView = binding.pager
                    .findViewHolderForAdapterPosition(position + 1)?.itemView
                val nextCard = nextView?.findViewById<FocusCardLayout>(R.id.card)
                if (nextView != null && nextCard != null) {
                    // 下一张始终不透明、铺满在下面：当前这张滑走时直接露出它，
                    // 不再用 alpha 淡入，否则滑走的空档会露出黑底（大黑屏闪一下）。
                    nextView.translationX = -nextView.width.toFloat()
                    nextView.translationZ = 0f
                    nextCard.alpha = 1f
                    nextCard.translationY = 0f
                    nextCard.scaleX = 0.985f
                    nextCard.scaleY = 0.985f
                    nextCard.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(260L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }

                // 当前这张：直接向上滑走，不再缩小/消失（方向等于「向上翻牌」）。
                val startOffset = if (itemView.translationY < 0f) {
                    itemView.translationY
                } else {
                    swipeOffsetY
                }
                card.translationY = startOffset
                card.animate()
                    .translationY(-itemView.height.toFloat())
                    .setDuration(260L)
                    .setInterpolator(DecelerateInterpolator(1.2f))
                    .withEndAction {
                        if (exitingView === itemView) {
                            removeWhenReady(position, page, itemView, nextView, nextCard)
                        }
                    }
                    .start()
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ) {
                binding.swipeGlow.alpha = 0f
                if (viewHolder.itemView !== exitingView) swipingView = null
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
                        // 列表已经更新、下一张已经就位于中心，这时再清掉临时位移。
                        pendingNextReset?.let { view ->
                            view.translationX = 0f
                            view.translationZ = 0f
                            view.findViewById<FocusCardLayout>(R.id.card)?.let {
                                it.alpha = 1f
                                it.translationY = 0f
                                it.scaleX = 1f
                                it.scaleY = 1f
                            }
                            pendingNextReset = null
                        }
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
        if (position != lastPreloadPosition) {
            lastPreloadPosition = position
            preloadAround(position)
        }
        if (page is FocusPage.Photo) {
            (application as LessApp).browseStore.markViewed(page.item.uri, false)
        }
    }

    /** 预取前后各一张，翻过去时直接命中缓存，不再等解码。 */
    private fun preloadAround(position: Int) {
        val loader = SingletonImageLoader.get(this)
        listOf(position + 1, position - 1).forEach { index ->
            val page = adapter.currentList.getOrNull(index) as? FocusPage.Photo ?: return@forEach
            runCatching {
                loader.enqueue(
                    ImageRequest.Builder(this)
                        .data(page.item.uri)
                        .size(1600)
                        .build()
                )
            }
        }
    }

    /**
     * 删除的收尾：等下一张的图片真正加载出来（最多等 1 秒）再把它从列表里移除，
     * 避免出现“标记完了但下一张还是空的”的停顿。
     */
    private fun removeWhenReady(
        position: Int,
        page: FocusPage.Photo,
        itemView: View,
        nextView: View?,
        nextCard: FocusCardLayout?,
        attempt: Int = 0
    ) {
        val loaded = nextView == null || nextCard?.photoView?.drawable != null
        if (!loaded && attempt < 20 && nextView?.parent != null) {
            binding.pager.postDelayed({
                removeWhenReady(position, page, itemView, nextView, nextCard, attempt + 1)
            }, 50L)
            return
        }

        exitingView = null
        pendingNextReset = nextView
        markForDeletion(position, page)
        itemView.translationY = 0f
        itemView.translationX = 0f
        itemView.translationZ = 0f
        itemView.scaleX = 1f
        itemView.scaleY = 1f
        itemView.alpha = 1f
        cardReset(itemView)
    }

    private fun cardReset(view: View) {
        view.findViewById<FocusCardLayout>(R.id.card)?.let {
            it.translationY = 0f
            it.translationX = 0f
            it.scaleX = 1f
            it.scaleY = 1f
            it.alpha = 1f
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
            .setNeutralButton(R.string.photo_info_open_gallery) { _, _ ->
                runCatching {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, page.item.uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    )
                }
            }
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
        val store = (application as LessApp).browseStore
        // 已收藏的原图不展示，但一起送进回收站，保持「收藏=移动」的语义。
        val selectedUris = selected.map { it.item.uri }.toSet()
        lastCleanupUris = store.pendingCleanupUris()
            .mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
            .filter { it !in selectedUris }
        lastDeleteItems = selected.map { it.item }
        viewModel.setBatchBusy(true)
        batchController.requestUris(selectedUris.toList() + lastCleanupUris, trash = true)
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
            (application as LessApp).browseStore.removePendingCleanup(lastCleanupUris)
            lastDeleteItems = emptyList()
            lastCleanupUris = emptyList()
            Toast.makeText(this, R.string.batch_trash_success, Toast.LENGTH_SHORT).show()
            // 删除完成直接进下一组，不再让用户二次确认。
            advanceAfterGroup()
        }
        refreshUndo()
    }

}
