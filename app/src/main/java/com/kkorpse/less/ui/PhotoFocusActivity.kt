package com.kkorpse.less.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kkorpse.less.Diagnostics
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
        /** 常见的 ColorOS / 原生相册包名，作为兜底显式打开。 */
        private val GALLERY_PACKAGES = listOf(
            "com.oplus.gallery",
            "com.coloros.gallery3d",
            "com.android.gallery3d"
        )
    }

    private lateinit var binding: ActivityPhotoFocusBinding
    private val viewModel: PhotoFocusViewModel by viewModels()

    private lateinit var batchController: BatchTrashController
    private var lastDeleteItems: List<MediaItem> = emptyList()
    private var lastCleanupUris: List<Uri> = emptyList()
    private var expandRequesting = false
    private var lastPreloadPosition = -1
    private var pages: List<FocusPage> = emptyList()
    private lateinit var favoriteController: FavoriteController
    private val locationResolver = PhotoLocationResolver()
    private val colorResolver = PhotoColorResolver()

    private var initialScrollDone = false
    private var lastGeneration = 0
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
        binding.deck.setPhotoActions(app.preferences.doubleTapAction, ::toggleFavorite)
        binding.deck.setSummaryCallbacks(object : DeckCardView.SummaryCallbacks {
            override fun onTogglePending(entry: PendingDelete) {
                viewModel.togglePendingSelection(entry.item)
            }

            override fun onConfirmDelete() = requestBatchDelete()

            override fun onDiscardGroup() = discardGroup()
        })
        binding.deck.listener = object : FocusDeckView.Listener {
            override fun onPageSettled(position: Int) {
                viewModel.currentIndex = position
                updateChrome(currentPage())
                if (position != lastPreloadPosition) {
                    lastPreloadPosition = position
                    preloadAround(position)
                }
                (currentPage() as? FocusPage.Photo)?.let {
                    app.browseStore.markViewed(it.item.uri, false)
                }
            }

            override fun onDeleteCommitted(page: FocusPage.Photo) {
                markForDeletion(page)
            }

            override fun onDeleteProgress(progress: Float) {
                binding.swipeGlow.alpha =
                    (progress / SWIPE_THRESHOLD).coerceIn(0f, 1f) * 0.9f
            }
        }

        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setupInsets()
        setupActions()

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
        if (this::binding.isInitialized) binding.deck.dispose()
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
            binding.deck.setContentInsets(contentTop, contentBottom)
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
    }

    private fun observePages() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.pages.collect { latest ->
                    pages = latest
                    val index = when {
                        !initialScrollDone -> {
                            initialScrollDone = true
                            lastGeneration = viewModel.groupGeneration
                            viewModel.currentIndex
                        }

                        viewModel.groupGeneration != lastGeneration -> {
                            lastGeneration = viewModel.groupGeneration
                            0
                        }

                        // 撤回：回到被恢复的那张。用掉就清掉，找不到也不留到下一次刷新。
                        pendingScrollOriginalIndex != null -> {
                            val originalIndex = pendingScrollOriginalIndex
                            pendingScrollOriginalIndex = null
                            latest.indexOfFirst {
                                it is FocusPage.Photo && it.originalIndex == originalIndex
                            }
                        }

                        // 其余情况（删除、展开）：停在原来那张上。
                        else -> -1
                    }
                    binding.deck.submit(latest, index)
                }
            }
        }
    }

    private fun currentPage(): FocusPage? = pages.getOrNull(binding.deck.currentIndex)

    private fun currentPhoto(): MediaItem? = (currentPage() as? FocusPage.Photo)?.item

    private fun updateCurrent() {
        viewModel.currentIndex = binding.deck.currentIndex
        updateChrome(currentPage())
    }

    /** 预取前后各一张，翻过去时直接命中缓存，不再等解码。 */
    private fun preloadAround(position: Int) {
        val loader = SingletonImageLoader.get(this)
        listOf(position + 1, position - 1).forEach { index ->
            val page = pages.getOrNull(index) as? FocusPage.Photo ?: return@forEach
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

    private fun updateChrome(page: FocusPage?) {
        when (page) {
            is FocusPage.Photo -> {
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
                binding.expandButton.isVisible = false
                locationJob?.cancel()
                binding.bottomBar.isVisible = false
                if (page.pending.isEmpty() && !page.busy && viewModel.autoNextGroup) {
                    viewModel.loadNextGroup()
                }
            }

            null -> {
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
                openInGallery(page.item)
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * 交给系统相册打开这张图。
     *
     * 必须带 MIME type（`setDataAndType`）：只给 content:// 时部分 ROM 解析不出类型，
     * 没有 Activity 能匹配，startActivity 直接抛异常，表现就是"点了没反应"。
     */
    private fun openInGallery(item: MediaItem) {
        val type = item.mimeType.takeIf { it.isNotBlank() } ?: "image/*"
        val base = Intent(Intent.ACTION_VIEW)
            .setDataAndType(item.uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val candidates = buildList {
            add(base)
            GALLERY_PACKAGES.forEach { pkg -> add(Intent(base).setPackage(pkg)) }
        }
        var lastError: Throwable? = null
        for (intent in candidates) {
            val result = runCatching { startActivity(intent) }
            if (result.isSuccess) return
            lastError = result.exceptionOrNull()
        }
        Diagnostics.recordError(item.uri.toString(), lastError)
        Toast.makeText(this, R.string.open_in_gallery_failed, Toast.LENGTH_SHORT).show()
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

    private fun markForDeletion(page: FocusPage.Photo) {
        if (batchController.isBusy) {
            // 批量流程进行中不能再标记，把牌堆拉回真实列表，避免少显示一张。
            binding.deck.submit(pages)
            return
        }
        if (viewModel.markForDeletion(page)) {
            binding.deck.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        } else {
            // 这一组已经进了汇总/批量流程，不让再标记；把牌堆拉回当前这张。
            binding.deck.submit(pages)
            Toast.makeText(this, R.string.summary_finish_first, Toast.LENGTH_SHORT).show()
        }
    }

    private fun undoLastMark() {
        if (batchController.isBusy) return
        // 先记下要回到哪一张：undoLastPending() 会同步触发一次页面刷新，
        // 晚一步设置就会错过这次刷新，结果"撤回后停在原地"，然后在下一次刷新时乱跳。
        val target = viewModel.pendingEntries().lastOrNull() ?: return
        pendingScrollOriginalIndex = target.originalIndex
        if (viewModel.undoLastPending() == null) {
            pendingScrollOriginalIndex = null
        }
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
