package com.kkorpse.less.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kkorpse.less.LessApp
import com.kkorpse.less.R
import com.kkorpse.less.data.MediaRepository
import com.kkorpse.less.databinding.ActivityStatsBinding
import com.kkorpse.less.util.AccessMode
import com.kkorpse.less.util.Format
import com.kkorpse.less.util.MediaAccess
import com.kkorpse.less.util.dp
import kotlinx.coroutines.launch

class StatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStatsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setupInsets()
        binding.back.setOnClickListener { finish() }
        binding.resetButton.setOnClickListener { confirmReset() }
    }

    override fun onResume() {
        super.onResume()
        loadStats()
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

    private fun loadStats() {
        lifecycleScope.launch {
            binding.loading.isVisible = true
            binding.partialNote.isVisible = MediaAccess.mode(this@StatsActivity) == AccessMode.PARTIAL

            val app = application as LessApp
            val result = MediaRepository(application).loadLibrary()
            val items = result.getOrElse { emptyList() }
            val photos = items.filter { !it.isVideo }
            val videos = items.filter { it.isVideo }

            val viewedPhotos = app.browseStore.viewedUris(false)
            val viewedVideos = app.browseStore.viewedUris(true)

            binding.photoTotal.text = Format.count(photos.size)
            binding.photoViewed.text = Format.count(
                photos.count { it.uri.toString() in viewedPhotos }
            )
            binding.photoDeleted.text = Format.count(app.browseStore.deletedPhotoCount)

            binding.videoTotal.text = Format.count(videos.size)
            binding.videoViewed.text = Format.count(
                videos.count { it.uri.toString() in viewedVideos }
            )
            binding.videoDeleted.text = Format.count(app.browseStore.deletedVideoCount)

            binding.loading.isVisible = false
        }
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.stats_reset_confirm_title)
            .setMessage(R.string.stats_reset_confirm_message)
            .setPositiveButton(R.string.stats_reset) { _, _ ->
                val app = application as LessApp
                app.browseStore.clearViewed()
                Toast.makeText(this, R.string.stats_reset_done, Toast.LENGTH_SHORT).show()
                loadStats()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
