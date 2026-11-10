package com.kkorpse.less.ui

import android.content.Intent
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
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
import com.kkorpse.less.databinding.FragmentPhotoHomeBinding
import com.kkorpse.less.prefs.BrowseMode
import com.kkorpse.less.util.dp
import kotlinx.coroutines.launch

class PhotoHomeFragment : Fragment() {

    private var _binding: FragmentPhotoHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PhotoHomeViewModel by viewModels()
    private var lastSettingsSignature = ""

    private val focusLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.nextGroup()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPhotoHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireActivity().application as LessApp
        lastSettingsSignature = settingsSignature()
        viewModel.state.value.let { state ->
            if (!state.loading) render(state)
        }

        binding.favoritesButton.setOnClickListener {
            startActivity(Intent(requireContext(), FavoritesActivity::class.java))
        }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        binding.emptyRefresh.setOnClickListener {
            viewModel.load(force = true)
        }

        setupInsets()
        observeState()
    }

    override fun onResume() {
        super.onResume()
        val signature = settingsSignature()
        if (lastSettingsSignature.isNotEmpty() && signature != lastSettingsSignature) {
            lastSettingsSignature = signature
            viewModel.load(force = true)
        } else {
            lastSettingsSignature = signature
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            binding.cardStack.alpha = 0.35f
            binding.cardStack.scaleX = 0.94f
            binding.cardStack.scaleY = 0.94f
            binding.cardStack.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(260)
                .start()
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun settingsSignature(): String {
        val prefs = (requireActivity().application as LessApp).preferences
        return "${prefs.groupSize}-${prefs.browseMode}"
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.topBar.updatePadding(top = requireContext().dp(12) + bars.top)
            insets
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    render(state)
                }
            }
        }
    }

    private fun render(state: PhotoHomeState) {
        binding.countBadge.text = if (state.photos.isEmpty()) "0" else state.photos.size.toString()
        binding.loading.isVisible = state.loading && state.photos.isEmpty()
        binding.emptyPanel.isVisible = !state.loading && state.photos.isEmpty()
        binding.photoContent.isVisible = state.photos.isNotEmpty()

        if (state.photos.isEmpty()) {
            binding.background.setImageDrawable(null)
            return
        }

        val app = requireActivity().application as LessApp
        val modeLabel = when (app.preferences.browseMode) {
            BrowseMode.RANDOM -> getString(R.string.browse_random)
            BrowseMode.SEQUENTIAL -> getString(R.string.browse_sequential)
        }
        binding.galleryPill.contentDescription = getString(
            R.string.photo_hint,
            app.preferences.groupSize,
            modeLabel
        )

        val photos = state.photos
        binding.background.load(photos[0].uri) {
            crossfade(200)
            placeholder(R.drawable.bg_thumb_placeholder)
            size(256, 256)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            binding.background.setRenderEffect(
                RenderEffect.createBlurEffect(42f, 42f, Shader.TileMode.CLAMP)
            )
        }

        binding.cardCenter.isVisible = true
        binding.cardLeft.isVisible = photos.size >= 2
        binding.cardRight.isVisible = photos.size >= 3

        bindCard(binding.cardCenter, photos[0], 0)
        if (photos.size >= 2) bindCard(binding.cardLeft, photos[1], 1)
        if (photos.size >= 3) bindCard(binding.cardRight, photos[2], 2)

        applyCardTransform(binding.cardLeft, -1f)
        applyCardTransform(binding.cardRight, 1f)
        binding.cardCenter.translationX = 0f
        binding.cardCenter.translationY = 0f
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
        val offsetX = resources.getDimensionPixelSize(R.dimen.photo_card_offset)
        val offsetY = resources.getDimensionPixelSize(R.dimen.photo_card_offset_y)
        card.translationX = direction * offsetX
        card.translationY = direction * offsetY
        card.rotation = 0f
    }

    private fun openFocus(index: Int) {
        val photos = viewModel.state.value.photos
        if (photos.isEmpty()) return
        PhotoFocusSession.items = photos
        PhotoFocusSession.startIndex = index.coerceIn(photos.indices)
        PhotoFocusSession.canTrash = true
        PhotoFocusSession.autoNextGroup = true
        focusLauncher.launch(Intent(requireContext(), PhotoFocusActivity::class.java))
    }
}
