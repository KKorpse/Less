package com.kkorpse.less.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kkorpse.less.LessApp
import com.kkorpse.less.R
import com.kkorpse.less.data.MediaRepository
import com.kkorpse.less.databinding.FragmentStatsBinding
import com.kkorpse.less.util.AccessMode
import com.kkorpse.less.util.Format
import com.kkorpse.less.util.MediaAccess
import com.kkorpse.less.util.dp
import kotlinx.coroutines.launch

class StatsFragment : Fragment() {

    private var _binding: FragmentStatsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupInsets()
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        binding.resetRow.setOnClickListener { confirmReset() }
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) loadStats()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) loadStats()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.topBar.updatePadding(top = requireContext().dp(6) + bars.top)
            insets
        }
    }

    private fun loadStats() {
        if (_binding == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            if (_binding == null) return@launch
            binding.loading.isVisible = true
            binding.partialNote.isVisible =
                MediaAccess.mode(requireContext()) == AccessMode.PARTIAL

            val app = requireActivity().application as LessApp
            val items = MediaRepository(requireContext()).loadLibrary().getOrElse { emptyList() }
            if (_binding == null) return@launch

            val photos = items.filter { !it.isVideo }
            val videos = items.filter { it.isVideo }
            val viewedPhotos = app.browseStore.viewedUris(false)
            val viewedVideos = app.browseStore.viewedUris(true)

            val photoViewed = photos.count { it.uri.toString() in viewedPhotos }
            val videoViewed = videos.count { it.uri.toString() in viewedVideos }
            val deletedTotal = app.browseStore.deletedPhotoCount + app.browseStore.deletedVideoCount

            binding.photoTotal.text = Format.count(photos.size)
            binding.photoViewed.text = Format.count(photoViewed)
            binding.photoDeleted.text = Format.count(app.browseStore.deletedPhotoCount)

            binding.videoTotal.text = Format.count(videos.size)
            binding.videoViewed.text = Format.count(videoViewed)
            binding.videoDeleted.text = Format.count(app.browseStore.deletedVideoCount)

            binding.resetSubtitle.text = getString(
                R.string.stats_reset_subtitle,
                photoViewed + videoViewed,
                deletedTotal
            )
            binding.loading.isVisible = false
        }
    }

    private fun confirmReset() {
        if (_binding == null) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.stats_reset_confirm_title)
            .setMessage(R.string.stats_reset_confirm_message)
            .setPositiveButton(R.string.stats_reset) { _, _ ->
                val app = requireActivity().application as LessApp
                app.browseStore.clearViewed()
                Toast.makeText(requireContext(), R.string.stats_reset_done, Toast.LENGTH_SHORT).show()
                loadStats()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
