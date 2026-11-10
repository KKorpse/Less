package com.kkorpse.less.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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
import androidx.fragment.app.Fragment
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

    enum class Tab { PHOTOS, VIDEOS, STATS }

    companion object {
        const val EXTRA_START_TAB = "extra_start_tab"
        const val TAB_VIDEO = "video"

        private const val TAG_PHOTOS = "tab_photos"
        private const val TAG_VIDEOS = "tab_videos"
        private const val TAG_STATS = "tab_stats"
        private const val STATE_TAB = "state_tab"
    }

    private lateinit var binding: ActivityMainBinding

    private var currentTab = Tab.PHOTOS
    private var visibleTab: Tab? = null
    private var permissionPermanentlyDenied = false
    private var fragmentsAdded = false
    private var launchedFromFavoritesVideo = false

    private var photoFragment: PhotoHomeFragment? = null
    private var videoFragment: VideoFeedFragment? = null
    private var statsFragment: StatsFragment? = null

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
        if (uris.isNotEmpty()) loadPicked(uris)
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

        launchedFromFavoritesVideo = intent.getStringExtra(EXTRA_START_TAB) == TAB_VIDEO
        val requestedTab = when {
            launchedFromFavoritesVideo -> Tab.VIDEOS
            else -> savedInstanceState?.getString(STATE_TAB)
                ?.let { runCatching { Tab.valueOf(it) }.getOrNull() }
                ?: Tab.PHOTOS
        }
        if (intent.getStringExtra(EXTRA_START_TAB) != TAB_VIDEO) {
            VideoFeedSession.items = emptyList()
            VideoFeedSession.startIndex = 0
        }
        currentTab = requestedTab

        setupInsets()
        setupActions()
        setupBack()
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAB, currentTab.name)
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.permissionHeader.updatePadding(top = dp(12) + bars.top)
            binding.permissionPanel.updatePadding(bottom = bars.bottom)
            (binding.bottomNav.layoutParams as android.widget.FrameLayout.LayoutParams).bottomMargin =
                resources.getDimensionPixelSize(R.dimen.float_nav_bottom_margin) + bars.bottom
            insets
        }
    }

    private fun setupActions() {
        binding.permissionSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.navPhotos.setOnClickListener { selectTab(Tab.PHOTOS) }
        binding.navVideos.setOnClickListener { selectTab(Tab.VIDEOS) }
        binding.navStats.setOnClickListener { selectTab(Tab.STATS) }
    }

    private fun setupBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentTab == Tab.VIDEOS && launchedFromFavoritesVideo &&
                    binding.tabContainer.isVisible
                ) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    return
                }
                if (currentTab != Tab.PHOTOS && binding.tabContainer.isVisible) {
                    selectTab(Tab.PHOTOS)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun render() {
        val mode = MediaAccess.mode(this)
        val granted = mode != AccessMode.NONE
        if (granted) permissionPermanentlyDenied = false

        binding.permissionPanel.isVisible = !granted
        binding.tabContainer.isVisible = granted
        binding.bottomNav.isVisible = granted
        binding.loading.isVisible = false

        if (granted) {
            ensureFragments()
            showTab(currentTab, force = visibleTab == null)
            if (currentTab == Tab.VIDEOS) {
                videoFragment?.resumeAfterPermission()
            }
        } else {
            videoFragment?.pauseForPermissionLoss()
            showPermissionStatus()
        }
    }

    private fun ensureFragments() {
        if (fragmentsAdded) return
        val fm = supportFragmentManager
        photoFragment = fm.findFragmentByTag(TAG_PHOTOS) as? PhotoHomeFragment
            ?: PhotoHomeFragment()
        videoFragment = fm.findFragmentByTag(TAG_VIDEOS) as? VideoFeedFragment
            ?: VideoFeedFragment()
        statsFragment = fm.findFragmentByTag(TAG_STATS) as? StatsFragment
            ?: StatsFragment()

        val tx = fm.beginTransaction()
        if (photoFragment?.isAdded != true) {
            tx.add(R.id.tabContainer, photoFragment!!, TAG_PHOTOS).hide(photoFragment!!)
        }
        if (videoFragment?.isAdded != true) {
            tx.add(R.id.tabContainer, videoFragment!!, TAG_VIDEOS).hide(videoFragment!!)
        }
        if (statsFragment?.isAdded != true) {
            tx.add(R.id.tabContainer, statsFragment!!, TAG_STATS).hide(statsFragment!!)
        }
        tx.commitNow()
        fragmentsAdded = true

        // 恢复场景下 FragmentManager 可能已经显示了某个 tab，先统一隐藏。
        val hide = fm.beginTransaction()
        photoFragment?.let { hide.hide(it) }
        videoFragment?.let { hide.hide(it) }
        statsFragment?.let { hide.hide(it) }
        hide.commitNow()
    }

    private fun selectTab(tab: Tab) {
        currentTab = tab
        if (MediaAccess.mode(this) == AccessMode.NONE) return
        ensureFragments()
        showTab(tab, force = false)
    }

    private fun showTab(tab: Tab, force: Boolean) {
        if (!fragmentsAdded) return
        val target: Fragment = when (tab) {
            Tab.PHOTOS -> photoFragment
            Tab.VIDEOS -> videoFragment
            Tab.STATS -> statsFragment
        } ?: return

        updateNavSelection(tab)

        if (!force && visibleTab == tab && target.isVisible) {
            binding.tabContainer.alpha = 1f
            return
        }

        fun commitTab() {
            val tx = supportFragmentManager.beginTransaction()
            photoFragment?.let { if (it !== target) tx.hide(it) }
            videoFragment?.let { if (it !== target) tx.hide(it) }
            statsFragment?.let { if (it !== target) tx.hide(it) }
            tx.show(target)
            tx.commit()
            visibleTab = tab
        }

        if (force || !binding.tabContainer.isVisible) {
            commitTab()
            binding.tabContainer.alpha = 1f
            return
        }

        binding.tabContainer.animate().cancel()
        binding.tabContainer.alpha = 1f
        binding.tabContainer.animate()
            .alpha(0f)
            .setDuration(100L)
            .withEndAction {
                commitTab()
                binding.tabContainer.alpha = 0f
                binding.tabContainer.animate()
                    .alpha(1f)
                    .setDuration(180L)
                    .start()
            }
            .start()
    }

    private fun updateNavSelection(tab: Tab) {
        binding.navPhotos.isSelected = tab == Tab.PHOTOS
        binding.navVideos.isSelected = tab == Tab.VIDEOS
        binding.navStats.isSelected = tab == Tab.STATS
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
                    Toast.makeText(this@MainActivity, R.string.error_generic, Toast.LENGTH_SHORT)
                        .show()
                } else {
                    MediaSession.items = items
                    startActivity(ViewerActivity.intent(this@MainActivity, 0))
                }
            }.onFailure {
                Toast.makeText(this@MainActivity, R.string.error_generic, Toast.LENGTH_SHORT)
                    .show()
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
