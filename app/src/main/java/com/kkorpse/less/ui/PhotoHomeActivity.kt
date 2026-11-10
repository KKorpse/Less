package com.kkorpse.less.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil3.load
import coil3.request.crossfade
import coil3.request.error
import coil3.request.placeholder
import com.google.android.material.imageview.ShapeableImageView
import com.kkorpse.less.LessApp
import com.kkorpse.less.R
import com.kkorpse.less.data.MediaItem
import com.kkorpse.less.data.PhotoFocusSession
import com.kkorpse.less.data.VideoFeedSession
import com.kkorpse.less.databinding.ActivityPhotoHomeBinding
import com.kkorpse.less.prefs.BrowseMode
import com.kkorpse.less.util.MediaAccess
import com.kkorpse.less.util.dp
import kotlinx.coroutines.launch

class PhotoHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoHomeBinding
    private val viewModel: PhotoHomeViewModel by viewModels()

    private val focusLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.nextGroup()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setupInsets()
        binding.back.setOnClickListener { finish() }
        binding.refresh.setOnClickListener { viewModel.load(force = true) }
        binding.emptyRefresh.setOnClickListener { viewModel.load(force = true) }
        binding.subtitle.text = getString(R.string.photo_subtitle)

        binding.navPhotos.isSelected = true
        binding.navVideos.setOnClickListener {
            VideoFeedSession.items = emptyList()
            VideoFeedSession.startIndex = 0
            startActivity(Intent(this, VideoFeedActivity::class.java))
        }
        binding.navStats.setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }

        observeState()
    }

    override fun onResume() {
        super.onResume()
        if (MediaAccess.mode(this) == com.kkorpse.less.util.AccessMode.NONE) {
            Toast.makeText(this, R.string.permission_denied_message, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.header.updatePadding(top = dp(10) + bars.top)
            binding.root.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    render(state)
                }
            }
        }
    }

    private fun render(state: PhotoHomeState) {
        binding.loading.isVisible = state.loading && state.photos.isEmpty()
        binding.emptyPanel.isVisible = !state.loading && state.photos.isEmpty()
        binding.photoContent.isVisible = state.photos.isNotEmpty()

        if (state.photos.isEmpty()) return

        val app = application as LessApp
        val modeLabel = when (app.preferences.browseMode) {
            BrowseMode.RANDOM -> getString(R.string.browse_random)
            BrowseMode.SEQUENTIAL -> getString(R.string.browse_sequential)
        }
        binding.hint.text = getString(
            R.string.photo_hint,
            app.preferences.groupSize,
            modeLabel
        )

        val photos = state.photos
        binding.cardCenter.isVisible = true
        binding.cardLeft.isVisible = photos.size >= 2
        binding.cardRight.isVisible = photos.size >= 3

        bindCard(binding.cardCenter, photos[0], 0)
        if (photos.size >= 2) bindCard(binding.cardLeft, photos[1], 1)
        if (photos.size >= 3) bindCard(binding.cardRight, photos[2], 2)

        applyCardTransform(binding.cardLeft, -1f)
        applyCardTransform(binding.cardRight, 1f)
        binding.cardCenter.translationX = 0f
        binding.cardCenter.translationY = -dp(8).toFloat()
        binding.cardCenter.rotation = 0f
    }

    private fun bindCard(card: ShapeableImageView, item: MediaItem, index: Int) {
        card.tag = index
        card.contentDescription = item.name
        card.load(item.uri) {
            crossfade(150)
            placeholder(R.drawable.bg_thumb_placeholder)
            error(R.drawable.bg_thumb_error)
            size(900, 900)
        }
        card.setOnClickListener { openFocus(index) }
    }

    private fun applyCardTransform(card: ShapeableImageView, direction: Float) {
        val offset = resources.getDimensionPixelSize(R.dimen.photo_card_offset)
        card.translationX = direction * offset
        card.translationY = dp(8).toFloat()
        card.rotation = direction * 9f
    }

    private fun openFocus(index: Int) {
        val photos = viewModel.state.value.photos
        if (photos.isEmpty()) return
        PhotoFocusSession.items = photos
        PhotoFocusSession.startIndex = index.coerceIn(photos.indices)
        PhotoFocusSession.canTrash = true
        focusLauncher.launch(Intent(this, PhotoFocusActivity::class.java))
    }
}
