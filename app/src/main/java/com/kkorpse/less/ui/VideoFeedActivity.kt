package com.kkorpse.less.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
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
import com.kkorpse.less.databinding.ActivityVideoFeedBinding
import com.kkorpse.less.util.Format
import com.kkorpse.less.util.dp
import kotlinx.coroutines.launch

class VideoFeedActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoFeedBinding
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
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityVideoFeedBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        viewModel.initialize(VideoFeedSession.items, VideoFeedSession.startIndex)

        player = ExoPlayer.Builder(this).build().apply {
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
                    adapter.showError(viewModel.currentIndex, true)
                    updatePlayIndicator()
                    Toast.makeText(
                        this@VideoFeedActivity,
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

        binding.back.bringToFront()
        binding.counter.bringToFront()
        binding.videoActions.isVisible = viewModel.state.value.videos.isNotEmpty()
        binding.videoInfo.isVisible = viewModel.state.value.videos.isNotEmpty()

        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setupInsets()
        setupActions()

        trashController = TrashController(this) { request, success ->
            handleTrashResult(request, success)
        }

        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
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

        observeState()
        updateOverlay(0)
    }

    override fun onResume() {
        super.onResume()
        if (viewModel.state.value.videos.isNotEmpty()) {
            if (player.mediaItemCount == 0) {
                playAt(viewModel.currentIndex)
            } else {
                player.play()
            }
        }
        handler.removeCallbacks(progressRunnable)
        handler.post(progressRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(progressRunnable)
        if (::player.isInitialized) player.pause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressRunnable)
        if (::player.isInitialized) player.release()
        super.onDestroy()
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
            (binding.videoActions.layoutParams as FrameLayout.LayoutParams).bottomMargin =
                dp(104) + bars.bottom
            (binding.videoInfo.layoutParams as FrameLayout.LayoutParams).bottomMargin =
                dp(28) + bars.bottom
            (binding.videoProgress.layoutParams as FrameLayout.LayoutParams).bottomMargin =
                bars.bottom
            insets
        }
    }

    private fun setupActions() {
        binding.back.setOnClickListener { finish() }
        binding.favoriteAction.setOnClickListener {
            currentItem()?.let { toggleFavorite(it) }
        }
        binding.deleteAction.setOnClickListener { deleteCurrent() }
        binding.undoAction.setOnClickListener { undoLast() }
        binding.emptyUndo.setOnClickListener { undoLast() }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    binding.loading.isVisible = state.loading && state.videos.isEmpty()
                    val empty = !state.loading && state.videos.isEmpty()
                    binding.emptyPanel.isVisible = empty
                    binding.videoActions.isVisible = state.videos.isNotEmpty()
                    binding.videoInfo.isVisible = state.videos.isNotEmpty()

                    adapter.submitList(state.videos) {
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
            if (retryCount++ < 3) {
                binding.pager.postDelayed({
                    if (binding.pager.currentItem == position) playAt(position)
                }, 60L)
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
        (application as LessApp).browseStore.markViewed(item.uri, true)
        updateOverlay(position)
        updatePlayIndicator()
    }

    private fun togglePlayback() {
        if (viewModel.state.value.videos.isEmpty()) return
        if (player.mediaItemCount == 0) {
            playAt(binding.pager.currentItem)
            return
        }
        if (player.isPlaying) player.pause() else player.play()
        updatePlayIndicator()
    }

    private fun toggleFavorite(item: MediaItem) {
        val app = application as LessApp
        val favorite = app.browseStore.toggleFavorite(item.uri)
        Toast.makeText(
            this,
            if (favorite) R.string.favorite_added else R.string.favorite_removed,
            Toast.LENGTH_SHORT
        ).show()
        updateFavorite(item)
    }

    private fun deleteCurrent() {
        val index = binding.pager.currentItem
        val item = currentItem() ?: return
        if (trashController.isBusy) return
        player.pause()
        trashController.request(item, trash = true, index = index)
        refreshActions()
    }

    private fun undoLast() {
        val entry = viewModel.peekUndo() ?: return
        if (trashController.isBusy) return
        player.pause()
        trashController.request(entry.item, trash = false, index = entry.index)
        refreshActions()
    }

    private fun handleTrashResult(request: TrashController.Request, success: Boolean) {
        if (request.trash) {
            if (success) {
                pendingPlayIndex = request.index
                viewModel.confirmTrash(request.item, request.index)
                Toast.makeText(this, R.string.trash_success, Toast.LENGTH_SHORT).show()
            } else {
                playAt(viewModel.currentIndex)
            }
        } else {
            if (success) {
                pendingPlayIndex = request.index
                viewModel.confirmRestore(request.item, request.index)
                Toast.makeText(this, R.string.restore_success, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.restore_failed, Toast.LENGTH_SHORT).show()
                playAt(viewModel.currentIndex)
            }
        }
        refreshActions()
    }

    private fun currentItem(): MediaItem? {
        return viewModel.state.value.videos.getOrNull(binding.pager.currentItem)
    }

    private fun updateOverlay(position: Int) {
        val videos = viewModel.state.value.videos
        val item = videos.getOrNull(position)
        if (item == null) {
            binding.counter.text = ""
            binding.fileName.text = ""
            binding.videoMeta.text = ""
            binding.playIndicator.isVisible = false
            binding.videoProgress.progress = 0
            binding.emptyUndo.isVisible = viewModel.canUndo.value
            return
        }

        binding.counter.text = getString(
            R.string.video_position,
            position + 1,
            videos.size
        )
        binding.fileName.text = item.name
        val meta = buildString {
            append(Format.relativeTime(item.dateMillis))
            if (item.durationMs > 0L) {
                append(" · ")
                append(Format.duration(item.durationMs))
            }
        }
        binding.videoMeta.text = meta
        updateFavorite(item)
        refreshActions()
        updatePlayIndicator()
    }

    private fun updateFavorite(item: MediaItem) {
        val favorite = (application as LessApp).browseStore.isFavorite(item.uri)
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

    private fun refreshActions() {
        val hasVideo = viewModel.state.value.videos.isNotEmpty()
        val canUndo = viewModel.canUndo.value && !trashController.isBusy
        binding.deleteAction.isEnabled = hasVideo && !trashController.isBusy
        binding.deleteAction.alpha = if (binding.deleteAction.isEnabled) 1f else 0.4f
        binding.undoAction.isEnabled = canUndo
        binding.undoAction.alpha = if (canUndo) 1f else 0.4f
        binding.emptyUndo.isVisible =
            viewModel.state.value.videos.isEmpty() && viewModel.canUndo.value
    }

    private fun updatePlayIndicator() {
        if (!::adapter.isInitialized || viewModel.state.value.videos.isEmpty()) {
            binding.playIndicator.isVisible = false
            return
        }
        binding.playIndicator.isVisible = !player.isPlaying
    }

    private fun updateProgress() {
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
