package com.kkorpse.less.ui

import android.content.res.ColorStateList
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
    private lateinit var trashController: TrashController

    private var attachedPosition = RecyclerView.NO_POSITION
    private var initialApplied = false
    private var retryCount = 0
    private var pendingPlayIndex: Int? = null

    private val handler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        trashController = TrashController(this, requireContext()) { request, success ->
            handleTrashResult(request, success)
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
                    adapter.showError(viewModel.currentIndex, true)
                    updatePlayIndicator()
                    Toast.makeText(
                        requireContext(),
                        R.string.viewer_video_error,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
        }

        adapter = VideoFeedAdapter()
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
        if (::player.isInitialized) player.release()
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
        if (viewModel.state.value.videos.isEmpty()) return
        val position = viewModel.currentIndex
        updateOverlay(position)
        if (player.mediaItemCount == 0 || attachedPosition != position) {
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
                requireContext().dp(140) + bars.bottom
            (binding.videoTime.layoutParams as FrameLayout.LayoutParams).bottomMargin =
                requireContext().dp(104) + bars.bottom
            (binding.videoProgress.layoutParams as FrameLayout.LayoutParams).bottomMargin =
                requireContext().dp(88) + bars.bottom
            insets
        }
    }

    private fun setupActions() {
        binding.favoriteAction.setOnClickListener {
            currentItem()?.let { toggleFavorite(it) }
        }
        binding.deleteAction.setOnClickListener { deleteCurrent() }
        binding.undoAction.setOnClickListener { undoLast() }
        binding.emptyUndo.setOnClickListener { undoLast() }

        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (_binding == null) return
                viewModel.setCurrentIndex(position)
                player.pause()
                if (attachedPosition != RecyclerView.NO_POSITION) {
                    adapter.attachPlayer(attachedPosition, null)
                }
                retryCount = 0
                playAt(position)
                updateOverlay(position)
            }
        })
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    if (_binding == null) return@collect
                    binding.loading.isVisible = state.loading && state.videos.isEmpty()
                    val empty = !state.loading && state.videos.isEmpty()
                    binding.emptyPanel.isVisible = empty
                    binding.videoActions.isVisible = state.videos.isNotEmpty()
                    binding.videoTime.isVisible = state.videos.isNotEmpty()

                    adapter.submitList(state.videos) {
                        if (_binding == null) return@submitList
                        if (state.videos.isEmpty()) {
                            resetAttachment()
                            player.stop()
                            player.clearMediaItems()
                            updateOverlay(0)
                            return@submitList
                        }

                        pendingPlayIndex?.let { requested ->
                            pendingPlayIndex = null
                            resetAttachment()
                            val target = requested.coerceIn(state.videos.indices)
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
                            val index = viewModel.currentIndex.coerceIn(state.videos.indices)
                            binding.pager.setCurrentItem(index, false)
                            updateOverlay(index)
                            binding.pager.post {
                                retryCount = 0
                                playAt(index)
                            }
                        } else {
                            val current = binding.pager.currentItem
                            if (current !in state.videos.indices) {
                                val next = (state.videos.size - 1).coerceAtLeast(0)
                                binding.pager.setCurrentItem(next, false)
                            }
                            updateOverlay(binding.pager.currentItem)
                        }
                    }
                }
            }
        }
    }

    private fun playAt(position: Int) {
        if (_binding == null) return
        val videos = viewModel.state.value.videos
        val item = videos.getOrNull(position) ?: return

        if (attachedPosition == position && player.mediaItemCount > 0) {
            player.play()
            updateOverlay(position)
            updatePlayIndicator()
            return
        }

        if (attachedPosition != RecyclerView.NO_POSITION && attachedPosition != position) {
            adapter.attachPlayer(attachedPosition, null)
        }

        if (adapter.playerViewAt(position) == null) {
            updateOverlay(position)
            if (retryCount++ < 8) {
                binding.pager.postDelayed({
                    if (_binding != null && binding.pager.currentItem == position) playAt(position)
                }, 80L)
            }
            return
        }

        adapter.attachPlayer(position, player)
        player.setMediaItem(androidx.media3.common.MediaItem.fromUri(item.uri))
        player.prepare()
        player.playWhenReady = true
        player.play()
        attachedPosition = position
        adapter.showError(position, false)

        viewModel.setCurrentIndex(position)
        (requireActivity().application as LessApp).browseStore.markViewed(item.uri, true)
        updateOverlay(position)
        updatePlayIndicator()
    }

    private fun toggleFavorite(item: MediaItem) {
        (requireActivity().application as LessApp).browseStore.toggleFavorite(item.uri)
        updateFavorite(item)
    }

    private fun deleteCurrent() {
        val index = binding.pager.currentItem
        val item = currentItem() ?: return
        if (trashController.isBusy == true) return
        player.pause()
        trashController.request(item, trash = true, index = index)
        refreshActions()
    }

    private fun undoLast() {
        val entry = viewModel.peekUndo() ?: return
        if (trashController.isBusy == true) return
        player.pause()
        trashController.request(entry.item, trash = false, index = entry.index)
        refreshActions()
    }

    private fun handleTrashResult(request: TrashController.Request, success: Boolean) {
        if (_binding == null) return
        if (request.trash) {
            if (success) {
                pendingPlayIndex = request.index
                viewModel.confirmTrash(request.item, request.index)
                Toast.makeText(requireContext(), R.string.trash_success, Toast.LENGTH_SHORT).show()
            } else {
                playAt(viewModel.currentIndex)
            }
        } else {
            if (success) {
                pendingPlayIndex = request.index
                viewModel.confirmRestore(request.item, request.index)
                Toast.makeText(requireContext(), R.string.restore_success, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), R.string.restore_failed, Toast.LENGTH_SHORT).show()
                playAt(viewModel.currentIndex)
            }
        }
        refreshActions()
    }

    private fun currentItem(): MediaItem? =
        viewModel.state.value.videos.getOrNull(binding.pager.currentItem)

    private fun updateOverlay(position: Int) {
        if (_binding == null) return
        val videos = viewModel.state.value.videos
        val item = videos.getOrNull(position)
        if (item == null) {
            binding.videoTime.isVisible = false
            binding.playIndicator.isVisible = false
            binding.videoProgress.progress = 0
            binding.emptyUndo.isVisible = viewModel.canUndo.value
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
        val hasVideo = viewModel.state.value.videos.isNotEmpty()
        val busy = trashController.isBusy == true
        val canUndo = viewModel.canUndo.value && !busy
        binding.deleteAction.isEnabled = hasVideo && !busy
        binding.deleteAction.alpha = if (binding.deleteAction.isEnabled) 1f else 0.4f
        binding.undoAction.isEnabled = canUndo
        binding.undoAction.alpha = if (canUndo) 1f else 0.4f
        binding.emptyUndo.isVisible = !hasVideo && viewModel.canUndo.value
    }

    private fun updatePlayIndicator() {
        if (_binding == null || !::player.isInitialized) return
        if (viewModel.state.value.videos.isEmpty()) {
            binding.playIndicator.isVisible = false
            return
        }
        binding.playIndicator.isVisible = !player.isPlaying
    }

    private fun updateProgress() {
        if (_binding == null || !::player.isInitialized) return
        val duration = player.duration
        if (duration > 0L) {
            binding.videoProgress.progress =
                ((player.currentPosition * 1000L) / duration).toInt().coerceIn(0, 1000)
        } else {
            binding.videoProgress.progress = 0
        }
    }

    private fun resetAttachment() {
        if (attachedPosition != RecyclerView.NO_POSITION) {
            adapter.attachPlayer(attachedPosition, null)
            attachedPosition = RecyclerView.NO_POSITION
        }
    }
}
