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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class StatsFragment : Fragment() {

    private var _binding: FragmentStatsBinding? = null
    private val binding get() = _binding!!

    private var loadJob: Job? = null
    private var photoProgress: RectProgressDrawable? = null
    private var videoProgress: RectProgressDrawable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsBinding.inflate(inflater, container, false)
        // 两张卡片本身就是进度条：边框从左上角开始按「已看过 / 总数」加粗。
        photoProgress = RectProgressDrawable(requireContext()).also {
            binding.photoCard.background = it
        }
        videoProgress = RectProgressDrawable(requireContext()).also {
            binding.videoCard.background = it
        }
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

    fun loadStats() {
        if (_binding == null) return
        // 同一时间只跑一次，切来切去不会叠出一堆查询。
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            if (_binding == null) return@launch
            binding.loading.isVisible = true
            binding.partialNote.isVisible =
                MediaAccess.mode(requireContext()) == AccessMode.PARTIAL

            val app = requireActivity().application as LessApp
            val store = app.browseStore
            // 只查 URI（单列投影），比把整行读出来快很多；总数包含收藏目录。
            val (photoUris, videoUris) = MediaRepository(requireContext()).listAllUris()
            // 已经复制到收藏、原图还没清理的那一张只算一份。
            val pendingCleanup = store.pendingCleanupUris()
            val photos = photoUris.filterNot { it in pendingCleanup }
            val videos = videoUris.filterNot { it in pendingCleanup }
            if (_binding == null) return@launch

            // 「查看」= 现存媒体里看过的数量（浏览记录和现存媒体求交集）：
            // 看过就算、重复看不重复计、删掉的自动不算，所以永远不会超过总数。
            // 收藏副本可能没有自己的浏览记录，按原图算，避免「查看」比实际少。
            val photoViewed = photos.count {
                store.isViewedDeep(android.net.Uri.parse(it), false)
            }
            val videoViewed = videos.count {
                store.isViewedDeep(android.net.Uri.parse(it), true)
            }

            // 进度条跟卡片上的两个数字保持一致：查看 / 总数（累计看过 ÷ 当前相册张数），
            // 看过的比相册张数还多（删过照片）时按 100% 显示。
            val photoFraction = if (photos.isEmpty()) 0f else {
                (photoViewed.toFloat() / photos.size).coerceIn(0f, 1f)
            }
            val videoFraction = if (videos.isEmpty()) 0f else {
                (videoViewed.toFloat() / videos.size).coerceIn(0f, 1f)
            }
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
            photoProgress?.animateTo(photoFraction)
            videoProgress?.animateTo(videoFraction)
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
