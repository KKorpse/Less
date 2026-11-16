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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kkorpse.less.LessApp
import com.kkorpse.less.R
import com.kkorpse.less.data.MediaItem
import com.kkorpse.less.data.PhotoFocusSession
import com.kkorpse.less.prefs.BrowseMode
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
        /** 上甩速度阈值（px/s），超过就执行删除。 */
        private const val FLING_VELOCITY = 900f
    }

    private lateinit var binding: ActivityPhotoFocusBinding
    private val viewModel: PhotoFocusViewModel by viewModels()

    private lateinit var adapter: PhotoFocusAdapter
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var batchController: BatchTrashController
    private var lastDeleteItems: List<MediaItem> = emptyList()
    private var lastCleanupUris: List<Uri> = emptyList()
    private var pendingNextReset: View? = null
    private var expandRequesting = false
    private var lastPreloadPosition = -1
    private var deckAnimator: android.animation.ValueAnimator? = null
    /** 上滑删除的唯一进度：拖动和收尾动画都写它。 */
    private var deckProgress = 0f
    /** 打断收尾动画重新拖动时的起始进度。 */
    private var deckResume = 0f
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
        setupDeck()

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
        // 离开页面时把收尾动画停掉，避免回到页面时卡片停在半路。
        deckAnimator?.cancel()
        deckAnimator = null
        deckProgress = 0f
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
            (binding.expandButton.layoutParams as FrameLayout.LayoutParams).topMargin =
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
        binding.expandButton.setOnClickListener { expandCurrent() }
        binding.pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    updateCurrent()
                    // 停止滚动后把不是当前页的子项收回原位：否则它们被我们钉在中间，
                    // 触摸命中时（findChildViewUnder 从后往前找）会抢到手势，导致删错图。
                    val current = currentPosition()
                    for (index in 0 until recyclerView.childCount) {
                        val child = recyclerView.getChildAt(index)
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

    /**
     * 竖直上滑删除：只有一份进度 p。
     * 拖动时 p 由手指决定，松手后 p 由动画走到 1（删除）或 0（回弹），
     * 渲染始终走同一个 applyDeck(p)，所以不存在两段动画。
     */
    private fun setupDeck() {
        binding.pager.listener = object : VerticalDeckRecyclerView.Listener {
            override fun onDeckDragStart() {
                // 收尾动画还没走完又按下：停在这里，从当前进度接着算，不回跳。
                deckAnimator?.cancel()
                deckAnimator = null
                deckResume = deckProgress
            }

            override fun onDeckDrag(progress: Float, dy: Float) {
                val height = binding.pager.height.coerceAtLeast(1).toFloat()
                val p = (deckResume + dy / height).coerceIn(0f, 1f)
                applyDeck(p)
                val glow = (p / SWIPE_THRESHOLD).coerceIn(0f, 1f)
                binding.swipeGlow.alpha = glow * 0.9f
            }

            override fun onDeckRelease(progress: Float, velocityY: Float) {
                settleDeck(deckProgress, velocityY)
            }
        }
    }

    private fun applyDeck(p: Float) {
        deckProgress = p
        val position = currentPosition()
        if (position == RecyclerView.NO_POSITION) return
        val itemView = binding.pager.findViewHolderForAdapterPosition(position)?.itemView ?: return
        val card = itemView.findViewById<FocusCardLayout>(R.id.card) ?: return

        DeckTransition.applyOutgoing(itemView, card, p, vertical = true)

        childAtPosition(position + 1)?.let { nextView ->
            nextView.translationX = -nextView.left.toFloat()
            nextView.translationZ = 0f
            nextView.findViewById<FocusCardLayout>(R.id.card)?.let { nextCard ->
                DeckTransition.applyIncoming(
                    nextView,
                    nextCard,
                    p,
                    vertical = true,
                    liftPx = dp72(),
                    opaque = false,
                    fromScale = 0.94f
                )
            }
        }
    }

    private fun settleDeck(progress: Float, velocityY: Float) {
        val commit = progress >= SWIPE_THRESHOLD || velocityY < -FLING_VELOCITY
        val target = if (commit) 1f else 0f
        val height = binding.pager.height.coerceAtLeast(1).toFloat()
        val distance = (kotlin.math.abs(target - progress) * height).toInt()
        deckAnimator?.cancel()
        deckAnimator = DeckTransition.animate(
            from = progress,
            to = target,
            duration = decelerationDuration(distance),
            onUpdate = { applyDeck(it) },
            onEnd = {
                deckAnimator = null
                binding.swipeGlow.animate().alpha(0f).setDuration(160L).start()
                if (commit) {
                    finishDeckSwipe()
                } else {
                    resetDeck()
                }
                deckProgress = 0f
                deckResume = 0f
            }
        )
    }

    /** 动画走完之后才标记删除。 */
    private fun finishDeckSwipe() {
        val position = currentPosition()
        if (position == RecyclerView.NO_POSITION) return
        val page = adapter.currentList.getOrNull(position) as? FocusPage.Photo ?: return

        // 先把「下一张」真正落到当前页（列表横向滚过去一屏）并同步归位变换：
        // 否则它还带着拖动时钉住它的 translationX，数据一变就会被画到屏幕外，出现黑闪。
        val next = childAtPosition(position + 1)
        runCatching { binding.pager.scrollBy(binding.pager.width, 0) }
        next?.let { view ->
            view.translationX = 0f
            view.translationZ = 0f
            view.findViewById<FocusCardLayout>(R.id.card)?.let { card ->
                DeckTransition.reset(view, card)
            }
        }

        pendingNextReset = next
        markForDeletion(position, page)
    }

    private fun resetDeck() {
        val position = currentPosition()
        if (position != RecyclerView.NO_POSITION) {
            binding.pager.findViewHolderForAdapterPosition(position)?.itemView?.let { view ->
                view.findViewById<FocusCardLayout>(R.id.card)?.let { card ->
                    DeckTransition.reset(view, card)
                }
            }
            childAtPosition(position + 1)?.let { view ->
                view.translationX = 0f
                view.translationZ = 0f
                view.findViewById<FocusCardLayout>(R.id.card)?.let { card ->
                    DeckTransition.reset(view, card)
                }
            }
        }
        binding.swipeGlow.alpha = 0f
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

    /**
     * 展开在随机模式下才有意义：顺序模式下相邻的那几张本来就在组里。
     * 收藏页（autoNextGroup=false）没有"组"的概念，也不显示。
     */
    private fun canExpand(): Boolean = viewModel.autoNextGroup &&
        (application as LessApp).preferences.browseMode == BrowseMode.RANDOM

    private fun expandCurrent() {
        val page = currentPage() as? FocusPage.Photo ?: return
        if (expandRequesting) return
        expandRequesting = true
        binding.expandButton.animate().alpha(0.35f).setDuration(120L).start()
        viewModel.expand(page) { count ->
            expandRequesting = false
            binding.expandButton.animate().alpha(1f).setDuration(120L).start()
            Toast.makeText(
                this,
                if (count > 0) getString(R.string.expand_added, count)
                else getString(R.string.expand_empty),
                Toast.LENGTH_SHORT
            ).show()
            updateChrome(currentPage())
        }
    }

    private fun dp72(): Float = dp(72).toFloat()

    /** 抄 LinearSmoothScroller：MILLISECONDS_PER_INCH = 25，除以 0.3356 得到减速时长。 */
    private fun decelerationDuration(distancePx: Int): Long {
        val densityDpi = resources.displayMetrics.densityDpi.toFloat().coerceAtLeast(1f)
        val millisPerPixel = 25f / densityDpi
        val linear = kotlin.math.ceil(kotlin.math.abs(distancePx) * millisPerPixel)
        return kotlin.math.ceil(linear / 0.3356f).toLong().coerceAtLeast(1L)
    }

    private fun childAtPosition(position: Int): View? {
        for (index in 0 until binding.pager.childCount) {
            val child = binding.pager.getChildAt(index)
            if (binding.pager.getChildAdapterPosition(child) == position) return child
        }
        return null
    }

    private fun updateChrome(page: FocusPage?) {
        when (page) {
            is FocusPage.Photo -> {
                binding.pager.deckEnabled = true
                binding.expandButton.isVisible = canExpand()
                binding.bottomBar.isVisible = true
                binding.favoriteButton.isEnabled = true
                binding.favoriteButton.alpha = 1f
                updateFavorite(page.item)
                refreshUndo()
                updateInfo(page)
                updatePageBackground(page)
            }

            is FocusPage.Summary -> {
                binding.pager.deckEnabled = false
                binding.expandButton.isVisible = false
                locationJob?.cancel()
                binding.bottomBar.isVisible = false
                if (page.pending.isEmpty() && !page.busy && viewModel.autoNextGroup) {
                    viewModel.loadNextGroup()
                }
            }

            null -> {
                binding.pager.deckEnabled = false
                binding.expandButton.isVisible = false
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
