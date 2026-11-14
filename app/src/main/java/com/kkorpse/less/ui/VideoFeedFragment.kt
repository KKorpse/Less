package com.kkorpse.less.ui

import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.kkorpse.less.LessApp
import com.kkorpse.less.R
import com.kkorpse.less.data.MediaItem
import com.kkorpse.less.data.VideoFeedSession
import com.kkorpse.less.databinding.FragmentVideoFeedBinding
import com.kkorpse.less.util.Format
import com.kkorpse.less.util.dp
import kotlinx.coroutines.launch

class VideoFeedFragment : Fragment() {

    private var _binding: FragmentVideoFeedBinding? = null
    private val binding get() = _binding!!

    private val viewModel: VideoFeedViewModel by viewModels()

    private lateinit var adapter: VideoFeedAdapter
    private lateinit var player: ExoPlayer
    private lateinit var batchController: BatchTrashController
    private lateinit var favoriteController: FavoriteController

    private var attachedPosition = RecyclerView.NO_POSITION
    private var attachedUri: Uri? = null
    private var initialApplied = false
    private var retryCount = 0
    private var pendingPlayPosition: Int? = null

    private val handler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 必须挂在 Fragment 上：VideoFeedFragment 是在 MainActivity.onResume 里才被 add 的，
        // 这时 Activity 已经 RESUMED，往 Activity 注册 launcher 会直接抛异常闪退。
        batchController = BatchTrashController(this, requireContext()) { trash, success ->
            handleBatchResult(trash, success)
        }
        favoriteController = FavoriteController(this, requireContext()) { request, success ->
            handleFavoriteResult(request, success)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVideoFeedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.initialize(VideoFeedSession.items, VideoFeedSession.startIndex)

        player = ExoPlayer.Builder(requireContext()).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            volume = 1f
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updatePlayIndicator()
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (_binding == null) return
                    adapter.showError(binding.pager.currentItem, true)
                    updatePlayIndicator()
                    Toast.makeText(
                        requireContext(),
                        R.string.viewer_video_error,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
        }

        adapter = VideoFeedAdapter(
            onBatchDelete = ::requestBatchDelete,
            onUndoBatch = ::requestBatchUndo,
            onNextGroup = ::nextGroup,
            onReview = {
                binding.pager.setCurrentItem(0, false)
            }
        )
        binding.pager.orientation = ViewPager2.ORIENTATION_VERTICAL
        binding.pager.offscreenPageLimit = 1
        binding.pager.adapter = adapter

        setupInsets()
        setupActions()
        observeState()
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) resumePlayback()
        handler.removeCallbacks(progressRunnable)
        handler.post(progressRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(progressRunnable)
        if (::player.isInitialized) player.pause()
        super.onPause()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!::player.isInitialized) return
        if (hidden) {
            player.pause()
            handler.removeCallbacks(progressRunnable)
        } else {
            binding.pager.post {
                resumePlayback()
            }
            handler.removeCallbacks(progressRunnable)
            handler.post(progressRunnable)
        }
    }

    override fun onDestroyView() {
        handler.removeCallbacks(progressRunnable)
        if (::player.isInitialized) {
            detachPlayer()
            player.release()
        }
        _binding = null
        super.onDestroyView()
    }

    fun pauseForPermissionLoss() {
        if (::player.isInitialized) player.pause()
        handler.removeCallbacks(progressRunnable)
    }

    fun resumeAfterPermission() {
        if (!isHidden) resumePlayback()
        handler.removeCallbacks(progressRunnable)
        handler.post(progressRunnable)
    }

    private fun resumePlayback() {
        val pages = viewModel.state.value.pages
        if (pages.none { it is VideoPage.Video }) return
        val position = currentVideoPosition() ?: return
        updateOverlay(position)
        if (attachedUri == null || attachedPosition != position) {
            binding.pager.post {
                retryCount = 0
                playAt(position)
            }
        } else {
            player.play()
        }
        updatePlayIndicator()
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            (binding.videoActions.layoutParams as FrameLayout.LayoutParams).bottomMargin =
                requireContext().dp(120) + bars.bottom
            (binding.videoTime.layoutParams as FrameLayout.LayoutParams).bottomMargin =
                requireContext().dp(88) + bars.bottom
            (binding.videoProgress.layoutParams as FrameLayout.LayoutParams).bottomMargin =
                requireContext().dp(74) + bars.bottom
            insets
        }
    }

    private fun setupActions() {
        binding.favoriteAction.setOnClickListener {
            currentItem()?.let { toggleFavorite(it) }
        }
        binding.deleteAction.setOnClickListener { markCurrentForDelete() }
        binding.undoAction.setOnClickListener { undoLastMark() }
        binding.emptyUndo.setOnClickListener { undoLastMark() }

        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (_binding == null) return
                viewModel.setCurrentIndex(position)
                val page = viewModel.state.value.pages.getOrNull(position)
                if (page is VideoPage.Video) {
                    binding.videoActions.isVisible = true
                    player.pause()
                    retryCount = 0
                    playAt(position)
                    updateOverlay(position)
                } else {
                    binding.videoActions.isVisible = false
                    stopPlayback()
                    updateSummaryChrome()
                }
            }
        })
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    if (_binding == null) return@collect
                    binding.loading.isVisible = state.loading && state.pages.isEmpty()

                    val hasVideo = state.pages.any { it is VideoPage.Video }
                    val summary = state.pages.filterIsInstance<VideoPage.Summary>().firstOrNull()
                    val hasMarks = summary != null &&
                        (summary.pending.isNotEmpty() || summary.deleted.isNotEmpty())
                    // 组里视频全被标记删除时也要能进汇总页，不能当成空列表。
                    val empty = !state.loading && !hasVideo && !hasMarks
                    binding.emptyPanel.isVisible = empty
                    binding.videoActions.isVisible = hasVideo
                    binding.videoTime.isVisible = hasVideo

                    adapter.submitList(state.pages) {
                        if (_binding == null) return@submitList
                        if (empty) {
                            stopPlayback()
                            return@submitList
                        }

                        val requested = pendingPlayPosition
                        pendingPlayPosition = null

                        if (requested != null) {
                            val target = requested.coerceIn(state.pages.indices)
                            binding.pager.setCurrentItem(target, false)
                            binding.pager.post {
                                retryCount = 0
                                playAt(target)
                            }
                            updateOverlay(target)
                            return@submitList
                        }

                        if (!initialApplied) {
                            initialApplied = true
                            val index = viewModel.currentIndex.coerceIn(state.pages.indices)
                            binding.pager.setCurrentItem(index, false)
                            updateOverlay(index)
                            binding.pager.post {
                                retryCount = 0
                                playAt(index)
                            }
                            return@submitList
                        }

                        val current = binding.pager.currentItem
                        val page = state.pages.getOrNull(current)
                        when (page) {
                            is VideoPage.Video -> playAt(current)
                            else -> {
                                stopPlayback()
                                updateSummaryChrome()
                            }
                        }
                    }
                }
            }
        }
    }

    /** 组尾汇总页：隐藏播放相关的浮层，显示汇总内容。 */
    private fun updateSummaryChrome() {
        binding.videoActions.isVisible = false
        binding.videoTime.isVisible = false
        binding.playIndicator.isVisible = false
        binding.videoProgress.progress = 0
        binding.emptyUndo.isVisible = false
    }

    private fun currentPage(): VideoPage? =
        viewModel.state.value.pages.getOrNull(binding.pager.currentItem)

    private fun currentItem(): MediaItem? = (currentPage() as? VideoPage.Video)?.item

    private fun currentVideoPosition(): Int? {
        val page = currentPage()
        return if (page is VideoPage.Video) binding.pager.currentItem else null
    }

    private fun playAt(position: Int) {
        if (_binding == null) return
        val page = viewModel.state.value.pages.getOrNull(position) as? VideoPage.Video
        if (page == null) {
            stopPlayback()
            return
        }
        val item = page.item

        if (attachedUri == item.uri && player.mediaItemCount > 0) {
            // 同一个视频换了位置：重新绑定画面，不重新 prepare，避免只有声音没有画面。
            adapter.attachPlayer(position, player)
            attachedPosition = position
            player.play()
            updateOverlay(position)
            updatePlayIndicator()
            return
        }

        if (adapter.playerViewAt(position) == null) {
            updateOverlay(position)
            if (retryCount++ < 12) {
                binding.pager.postDelayed({
                    if (_binding != null && binding.pager.currentItem == position) playAt(position)
                }, 80L)
            }
            return
        }

        detachPlayer()
        adapter.attachPlayer(position, player)
        player.setMediaItem(androidx.media3.common.MediaItem.fromUri(item.uri))
        player.prepare()
        player.playWhenReady = true
        player.play()
        attachedPosition = position
        attachedUri = item.uri
        adapter.showError(position, false)

        viewModel.setCurrentIndex(position)
        (requireActivity().application as LessApp).browseStore.markViewed(item.uri, true)
        updateOverlay(position)
        updatePlayIndicator()
    }

    private fun detachPlayer() {
        if (attachedPosition != RecyclerView.NO_POSITION) {
            adapter.attachPlayer(attachedPosition, null)
        }
        attachedPosition = RecyclerView.NO_POSITION
        attachedUri = null
        if (::player.isInitialized && player.mediaItemCount > 0) {
            player.stop()
            player.clearMediaItems()
        }
    }

    private fun stopPlayback() {
        detachPlayer()
        if (_binding == null) return
        binding.playIndicator.isVisible = false
        binding.videoProgress.progress = 0
    }

    private fun markCurrentForDelete() {
        val position = binding.pager.currentItem
        val item = currentItem() ?: return
        player.pause()
        pendingPlayPosition = position
        if (!viewModel.markForDeletion(item)) {
            pendingPlayPosition = null
            return
        }
        binding.pager.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun undoLastMark() {
        val item = viewModel.undoLastMark() ?: return
        pendingPlayPosition = viewModel.state.value.pages
            .indexOfFirst { it is VideoPage.Video && it.item.uri == item.uri }
            .takeIf { it >= 0 }
    }

    private fun requestBatchDelete() {
        val pending = viewModel.pendingItems()
        if (pending.isEmpty() || batchController.isBusy) return
        player.pause()
        stopPlayback()
        viewModel.setBatchBusy(true)
        batchController.request(pending, trash = true)
        updateSummaryChrome()
    }

    private fun requestBatchUndo() {
        val deleted = viewModel.deletedItems()
        if (deleted.isEmpty() || batchController.isBusy) return
        viewModel.setBatchBusy(true)
        batchController.request(deleted, trash = false)
    }

    private fun handleBatchResult(trash: Boolean, success: Boolean) {
        if (_binding == null) return
        if (!success) {
            viewModel.setBatchBusy(false)
            return
        }
        if (trash) {
            viewModel.markBatchDeleted()
            Toast.makeText(requireContext(), R.string.trash_success, Toast.LENGTH_SHORT).show()
        } else {
            viewModel.undoBatchDeleted()
            Toast.makeText(requireContext(), R.string.restore_success, Toast.LENGTH_SHORT).show()
        }
    }

    private fun nextGroup() {
        if (viewModel.hasNextGroup()) {
            viewModel.nextGroup()
            binding.pager.post { binding.pager.setCurrentItem(0, false) }
        } else {
            requireActivity().finish()
        }
    }

    private fun toggleFavorite(item: MediaItem) {
        val store = (requireActivity().application as LessApp).browseStore
        val favorite = !store.isFavorite(item.uri)
        // 先更新本地镜像和 UI，再尝试写入系统媒体库；系统写入失败时回滚。
        store.setFavorite(item.uri, favorite)
        updateFavorite(item)
        if (!favoriteController.request(item, favorite)) {
            store.markLocalOnly(item.uri, favorite)
        }
    }

    private fun handleFavoriteResult(request: FavoriteController.Request, success: Boolean) {
        if (success || _binding == null) return
        val store = (requireActivity().application as LessApp).browseStore
        request.items.forEach { store.setFavorite(it.uri, !request.favorite) }
        currentItem()?.let { updateFavorite(it) }
        Toast.makeText(requireContext(), R.string.favorite_sync_failed, Toast.LENGTH_SHORT).show()
    }

    private fun updateOverlay(position: Int) {
        if (_binding == null) return
        val page = viewModel.state.value.pages.getOrNull(position)
        val item = (page as? VideoPage.Video)?.item
        if (item == null) {
            updateSummaryChrome()
            return
        }

        val relative = Format.relativeTime(item.dateMillis)
        binding.videoTime.text = if (item.durationMs > 0L) {
            getString(R.string.video_time_duration, relative, Format.duration(item.durationMs))
        } else {
            relative
        }
        binding.videoTime.isVisible = true
        updateFavorite(item)
        refreshActions()
        updatePlayIndicator()
    }

    private fun updateFavorite(item: MediaItem) {
        val favorite = (requireActivity().application as LessApp).browseStore.isFavorite(item.uri)
        binding.favoriteAction.setImageResource(
            if (favorite) R.drawable.ic_heart_filled else R.drawable.ic_heart
        )
        binding.favoriteAction.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                requireContext(),
                if (favorite) R.color.heart else R.color.accent
            )
        )
        binding.favoriteAction.contentDescription = getString(
            if (favorite) R.string.unfavorite_content_desc else R.string.favorite_content_desc
        )
    }

    private fun refreshActions() {
        val hasVideo = currentPage() is VideoPage.Video
        val busy = batchController.isBusy
        binding.deleteAction.isEnabled = hasVideo && !busy
        binding.deleteAction.alpha = if (binding.deleteAction.isEnabled) 1f else 0.4f
        val canUndo = viewModel.state.value.pages.any {
            it is VideoPage.Summary && it.pending.isNotEmpty()
        } && !busy
        binding.undoAction.isEnabled = canUndo
        binding.undoAction.alpha = if (canUndo) 1f else 0.4f
        binding.emptyUndo.isVisible = false
    }

    private fun updatePlayIndicator() {
        if (_binding == null || !::player.isInitialized) return
        val page = currentPage()
        if (page !is VideoPage.Video) {
            binding.playIndicator.isVisible = false
            return
        }
        binding.playIndicator.isVisible = !player.isPlaying
    }

    private fun updateProgress() {
        if (_binding == null || !::player.isInitialized) return
        val duration = player.duration
        if (duration <= 0L) return
        val progress = (player.currentPosition * 1000 / duration).coerceIn(0L, 1000L)
        binding.videoProgress.progress = progress.toInt()
    }
}
