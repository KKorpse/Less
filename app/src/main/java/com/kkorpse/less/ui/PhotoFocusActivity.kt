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
    private var deleteAnimating = false
    private var dragPreAttached = false
    private var dragNextPinned = false
    private var deleteDragPx = 0f
    private val velocityTracker = android.view.VelocityTracker.obtain()
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

        layoutManager = PeekingLinearLayoutManager(this)
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
        // 离开页面时把手势状态清干净，避免锁残留。
        dragPreAttached = false
        // 只记录从首页进入的组；收藏页进来的会话不覆盖首页进度。
        if (!PhotoFocusSession.autoNextGroup) return
        val app = application as LessApp
        val mode = app.preferences.browseMode.name
        app.browseStore.setGroupIndex(mode, viewModel.currentIndex)
    }

    override fun onDestroy() {
        runCatching { velocityTracker.recycle() }
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
        // 记录松手瞬间的竖直速度，用于把上滑的收尾时长按「距离 + 速度」算，
        // 与左滑吸附（SnapHelper 同样按距离/速度决定时长）保持一致。
        binding.pager.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean {
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        velocityTracker.clear()
                        velocityTracker.addMovement(e)
                    }
                    android.view.MotionEvent.ACTION_MOVE -> velocityTracker.addMovement(e)
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        velocityTracker.addMovement(e)
                        velocityTracker.computeCurrentVelocity(1000)
                    }
                }
                return false
            }
        })

        binding.pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    updateCurrent()
                    // 停止滚动后把不是当前页的子项收回原位：否则它们被我们钉在中间，
                    // 触摸命中时（findChildViewUnder 从后往前找）会抢到手势，导致删错图。
                    val current = currentPosition()
                    for (index in 0 until recyclerView.childCount) {
                        val child = recyclerView.getChildAt(index)
                        if (child === exitingView) continue
                        if (recyclerView.getChildAdapterPosition(child) != current) {
                            child.translationX = 0f
                            child.translationZ = 0f
                            child.findViewById<FocusCardLayout>(R.id.card)?.let {
                                it.alpha = 1f
                                it.translationY = 0f
                                it.scaleX = 1f
                                it.scaleY = 1f
                            }
                        }
                    }
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                // 横向翻页走同一套发牌模型：当前页随手指横向滑走，
                // 后面那张钉到中间、从下方浮起并放大。
                val width = recyclerView.width.toFloat().coerceAtLeast(1f)
                val center = width / 2f
                for (index in 0 until recyclerView.childCount) {
                    val child = recyclerView.getChildAt(index)
                    if (child === exitingView || child === swipingView) continue
                    val card = child.findViewById<FocusCardLayout>(R.id.card) ?: continue
                    val childCenter = (child.left + child.right) / 2f
                    val offset = ((childCenter - center) / width).coerceIn(-1f, 1f)
                    if (offset > 0f) {
                        DeckTransition.applyIncoming(
                            child,
                            card,
                            p = 1f - offset,
                            vertical = false,
                            liftPx = dp(72).toFloat(),
                            opaque = false
                        )
                    } else {
                        DeckTransition.applyOutgoing(child, card, -offset, vertical = false)
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
                val position = viewHolder.bindingAdapterPosition
                val page = adapter.currentList.getOrNull(position)
                // 只允许当前正在看的那张上滑，避免手势落在被钉在中间的相邻页上
                return if (!deleteAnimating &&
                    binding.pager.scrollState == RecyclerView.SCROLL_STATE_IDLE &&
                    page is FocusPage.Photo &&
                    position == currentPosition()
                ) {
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
                        // 竖直拖动期间不要让它同时带动横向翻页
                        recyclerView.stopScroll()
                        // 拖动一开始就让 RecyclerView 把下一张布局出来（1px 不可见），
                        // 这样手指往上拖的过程中，下面就一直是完整的那张。
                        if (!dragPreAttached) {
                            dragPreAttached = true
                            // 先滚 1px 把下一张布局出来，再上锁（锁住后 scrollBy 会被忽略）
                            recyclerView.scrollBy(1, 0)
                        }
                        swipeOffsetY = dY
                        deleteDragPx = (-dY).coerceAtLeast(0f)
                        // 拖动阶段就让下一张跟着手指"由暗变亮 + 浮起"，不等松手。
                        childAtPosition(currentPosition() + 1)?.let { nextView ->
                            val nextCard = nextView.findViewById<FocusCardLayout>(R.id.card)
                            if (nextCard != null) {
                                if (!dragNextPinned) {
                                    nextView.translationX = -nextView.left.toFloat()
                                    nextView.translationZ = 0f
                                    dragNextPinned = true
                                }
                                val dp = (deleteDragPx / itemView.height).coerceIn(0f, 1f)
                                nextCard.alpha = dp
                                nextCard.translationY = (1f - dp) * dp72()
                                val nextScale = 0.94f + 0.06f * dp
                                nextCard.scaleX = nextScale
                                nextCard.scaleY = nextScale
                            }
                        }
                        // 以「触发删除的距离」为 1，红光与缩小同步渐变。
                        val progress = (-dY / (itemView.height * SWIPE_THRESHOLD))
                            .coerceIn(0f, 1f)
                        // 和左滑一致：实心、不缩小，只是跟着手指上移
                        card.scaleX = 1f
                        card.scaleY = 1f
                        card.alpha = 1f
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
                val position = currentPosition().takeIf { it != RecyclerView.NO_POSITION }
                    ?: return
                if (deleteAnimating) return
                val page = adapter.currentList.getOrNull(position) as? FocusPage.Photo ?: return

                binding.swipeGlow.animate().alpha(0f).setDuration(200L).start()

                val itemView = viewHolder.itemView
                val card = itemView.findViewById<FocusCardLayout>(R.id.card)
                val nextView = childAtPosition(position + 1)
                val nextCard = nextView?.findViewById<FocusCardLayout>(R.id.card)
                if (card == null || nextView == null || nextCard == null) {
                    markForDeletion(position, page)
                    return
                }

                if (!dragNextPinned) {
                    nextView.translationX = -nextView.left.toFloat()
                    nextView.translationZ = 0f
                    dragNextPinned = true
                }

                // 和左滑同一套参数，从拖动阶段已有的进度接着走。
                val height = itemView.height.coerceAtLeast(1).toFloat()
                val startP = (deleteDragPx / height).coerceIn(0f, 1f)
                val speed = kotlin.math.abs(velocityTracker.yVelocity).coerceAtLeast(600f)
                val duration = ((1f - startP) * height / speed * 1000f * 1.6f)
                    .toLong().coerceIn(120L, 340L)

                deleteAnimating = true
                exitingView = itemView
                DeckTransition.animate(
                    from = startP,
                    to = 1f,
                    duration = duration,
                    onUpdate = { p ->
                        DeckTransition.applyOutgoing(itemView, card, p, vertical = true)
                        DeckTransition.applyIncoming(
                            nextView,
                            nextCard,
                            p,
                            vertical = true,
                            liftPx = dp72(),
                            opaque = false,
                            fromScale = 0.94f
                        )
                    },
                    onEnd = {
                        removeWhenReady(position, page, itemView, nextView, nextCard)
                    }
                )
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ) {
                binding.swipeGlow.alpha = 0f
                dragPreAttached = false
                if (dragNextPinned) {
                    childAtPosition(currentPosition() + 1)?.let { nextView ->
                        nextView.translationX = 0f
                        nextView.translationZ = 0f
                        nextView.findViewById<FocusCardLayout>(R.id.card)?.let {
                            it.alpha = 1f
                            it.translationY = 0f
                            it.scaleX = 1f
                            it.scaleY = 1f
                        }
                    }
                    dragNextPinned = false
                }

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
                            val card = view.findViewById<FocusCardLayout>(R.id.card)
                            if (card != null) DeckTransition.reset(view, card)
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

    private fun dp72(): Float = dp(72).toFloat()

    private fun childAtPosition(position: Int): View? {
        for (index in 0 until binding.pager.childCount) {
            val child = binding.pager.getChildAt(index)
            if (binding.pager.getChildAdapterPosition(child) == position) return child
        }
        return null
    }

    /**
     * 删除的收尾：等下一张的图片真正加载出来（最多 1 秒）再把它从列表里移除。
     * 不移除前先不动视图，避免"要删的这张"被复位拉回视野闪一下。
     */
    private fun removeWhenReady(
        position: Int,
        page: FocusPage.Photo,
        itemView: View,
        nextView: View?,
        nextCard: FocusCardLayout?,
        attempt: Int = 0
    ) {
        val loaded = nextCard?.photoView?.drawable != null
        if (!loaded && attempt < 20 && nextView?.parent != null) {
            binding.pager.postDelayed({
                removeWhenReady(position, page, itemView, nextView, nextCard, attempt + 1)
            }, 50L)
            return
        }

        exitingView = null
        deleteAnimating = false
        dragNextPinned = false
        deleteDragPx = 0f
        pendingNextReset = nextView
        markForDeletion(position, page)
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
