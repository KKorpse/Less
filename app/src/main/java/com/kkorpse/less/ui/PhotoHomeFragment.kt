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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kkorpse.less.LessApp
import com.kkorpse.less.R
import com.kkorpse.less.data.MediaBucket
import com.kkorpse.less.data.MediaItem
import com.kkorpse.less.data.MediaRepository
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
    private lateinit var repository: MediaRepository

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
        binding.albumPill.setOnClickListener { showAlbumPicker() }
        binding.emptyRefresh.setOnClickListener {
            viewModel.load(force = true)
        }

        repository = MediaRepository(
            requireContext(),
            (requireActivity().application as LessApp).browseStore
        )

        setupInsets()
        observeState()
    }

    /**
     * 首页相册切换：列出所有含照片的文件夹（MediaStore bucket），
     * 选「系统相册」就回到全部照片。
     */
    private fun showAlbumPicker() {
        val app = requireActivity().application as LessApp
        viewLifecycleOwner.lifecycleScope.launch {
            val buckets = repository.loadBuckets().getOrElse { emptyList() }
            if (_binding == null) return@launch

            val labels = ArrayList<String>(buckets.size + 1)
            labels += getString(R.string.album_all)
            buckets.forEach { labels += getString(R.string.album_item, it.name, it.count) }

            val selectedId = app.preferences.photoBucketId
            val checked = buckets.indexOfFirst { it.id == selectedId }
                .let { if (it >= 0) it + 1 else 0 }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.album_picker_title)
                .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                    applyBucket(if (which == 0) null else buckets[which - 1], app)
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun applyBucket(bucket: MediaBucket?, app: LessApp) {
        app.preferences.photoBucketId = bucket?.id
        app.preferences.photoBucketName = bucket?.name
        viewModel.load(force = true)
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
        val preferences = (requireActivity().application as LessApp).preferences
        val albumLabel = preferences.photoBucketName?.takeIf { it.isNotBlank() }
            ?: getString(R.string.album_default)
        binding.albumName.text = albumLabel
        binding.albumPill.contentDescription =
            getString(R.string.album_pill_desc, albumLabel)

        binding.countBadge.text = if (state.photos.isEmpty()) "0" else state.photos.size.toString()
        binding.loading.isVisible = state.loading && state.photos.isEmpty()
        binding.emptyPanel.isVisible = !state.loading && state.photos.isEmpty()
        binding.photoContent.isVisible = state.photos.isNotEmpty()

        if (state.photos.isEmpty()) {
            binding.background.setImageDrawable(null)
            binding.emptyMessage.setText(
                if (preferences.photoBucketId != null) R.string.album_empty_message
                else R.string.photo_empty_message
            )
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
        binding.cardCenter.translationY = -requireContext().dp(8).toFloat()
        binding.cardCenter.rotation = 0f
    }

    private fun bindCard(card: FanCardView, item: MediaItem, index: Int) {
        card.tag = index
        card.contentDescription = item.name
        card.imageView.load(item.uri) {
            crossfade(150)
            placeholder(R.drawable.bg_thumb_placeholder)
            error(R.drawable.bg_thumb_error)
            size(900, 900)
        }
        card.setOnClickListener { openFocus(index) }
    }

    private fun applyCardTransform(card: FanCardView, direction: Float) {
        val offset = resources.getDimensionPixelSize(R.dimen.photo_card_offset)
        card.translationX = direction * offset
        card.translationY = requireContext().dp(8).toFloat()
        card.rotation = direction * 9f
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
