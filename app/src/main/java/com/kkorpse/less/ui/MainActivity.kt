package com.kkorpse.less.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.kkorpse.less.R
import com.kkorpse.less.data.MediaRepository
import com.kkorpse.less.data.MediaSession
import com.kkorpse.less.data.VideoFeedSession
import com.kkorpse.less.databinding.ActivityMainBinding
import com.kkorpse.less.util.AccessMode
import com.kkorpse.less.util.MediaAccess
import com.kkorpse.less.util.dp
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var permissionPermanentlyDenied = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val mode = MediaAccess.mode(this)
        permissionPermanentlyDenied =
            mode == AccessMode.NONE && MediaAccess.isPermanentlyDenied(this)
        render()
    }

    private val pickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            loadPicked(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setupInsets()
        setupActions()
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.header.updatePadding(top = dp(12) + bars.top)
            binding.root.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    private fun setupActions() {
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.favoritesButton.setOnClickListener {
            startActivity(Intent(this, FavoritesActivity::class.java))
        }
        binding.partialManage.setOnClickListener {
            permissionLauncher.launch(MediaAccess.requiredPermissions())
        }
        binding.buttonPhotos.setOnClickListener {
            startActivity(Intent(this, PhotoHomeActivity::class.java))
        }
        binding.buttonVideos.setOnClickListener {
            VideoFeedSession.items = emptyList()
            VideoFeedSession.startIndex = 0
            startActivity(Intent(this, VideoFeedActivity::class.java))
        }
        binding.buttonStats.setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }
    }

    private fun render() {
        val mode = MediaAccess.mode(this)
        val granted = mode != AccessMode.NONE
        if (granted) permissionPermanentlyDenied = false

        binding.homeContent.isVisible = granted
        binding.favoritesButton.isVisible = granted
        binding.partialBanner.isVisible = mode == AccessMode.PARTIAL
        binding.statusPanel.isVisible = !granted
        binding.loading.isVisible = false

        if (!granted) {
            showPermissionStatus()
        }
    }

    private fun showPermissionStatus() {
        if (permissionPermanentlyDenied) {
            showStatus(
                title = getString(R.string.permission_denied_title),
                message = getString(R.string.permission_denied_message),
                primaryText = getString(R.string.permission_settings),
                onPrimary = { openAppSettings() }
            )
        } else {
            showStatus(
                title = getString(R.string.permission_title),
                message = getString(R.string.permission_message),
                primaryText = getString(R.string.permission_allow),
                onPrimary = { permissionLauncher.launch(MediaAccess.requiredPermissions()) }
            )
        }
    }

    private fun showStatus(
        title: String,
        message: String,
        primaryText: String,
        onPrimary: () -> Unit
    ) {
        binding.statusTitle.text = title
        binding.statusMessage.text = message
        binding.statusPrimary.text = primaryText
        binding.statusPrimary.setOnClickListener { onPrimary() }
        binding.statusSecondary.isVisible = true
        binding.statusSecondary.text = getString(R.string.use_picker)
        binding.statusSecondary.setOnClickListener { launchPicker() }
    }

    private fun launchPicker() {
        pickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
        )
    }

    private fun loadPicked(uris: List<Uri>) {
        lifecycleScope.launch {
            binding.loading.isVisible = true
            val result = MediaRepository(application).loadPicked(uris)
            binding.loading.isVisible = false

            result.onSuccess { items ->
                if (items.isEmpty()) {
                    Toast.makeText(this@MainActivity, R.string.error_generic, Toast.LENGTH_SHORT).show()
                } else {
                    MediaSession.items = items
                    startActivity(ViewerActivity.intent(this@MainActivity, 0))
                }
            }.onFailure {
                Toast.makeText(this@MainActivity, R.string.error_generic, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:$packageName".toUri()
        )
        runCatching { startActivity(intent) }
    }
}
