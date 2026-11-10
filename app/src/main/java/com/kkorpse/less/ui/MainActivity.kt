package com.kkorpse.less.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.kkorpse.less.R
import com.kkorpse.less.data.MediaItem
import com.kkorpse.less.data.MediaSession
import com.kkorpse.less.databinding.ActivityMainBinding
import com.kkorpse.less.util.Format
import com.kkorpse.less.util.dp
import kotlinx.coroutines.launch
import java.time.LocalDate

class MainActivity : AppCompatActivity() {

    private enum class AccessMode { NONE, PARTIAL, FULL }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: GalleryViewModel by viewModels()
    private lateinit var adapter: GalleryAdapter

    private var lastLoadedMode = AccessMode.NONE
    private var permissionPermanentlyDenied = false
    private var errorToastShown = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val mode = accessMode()
        if (mode == AccessMode.NONE) {
            permissionPermanentlyDenied = requiredPermissions().none {
                shouldShowRequestPermissionRationale(it)
            }
            render(viewModel.state.value)
        } else {
            permissionPermanentlyDenied = false
            lastLoadedMode = mode
            viewModel.loadLibrary()
            render(viewModel.state.value)
        }
    }

    private val pickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.openPicked(uris)
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
        setupGrid()
        setupActions()
        observeState()

        val mode = accessMode()
        if (mode != AccessMode.NONE) {
            lastLoadedMode = mode
            viewModel.loadLibrary()
        }
        render(viewModel.state.value)
    }

    override fun onResume() {
        super.onResume()
        val mode = accessMode()
        if (mode != AccessMode.NONE) {
            permissionPermanentlyDenied = false
            if (mode != lastLoadedMode) {
                lastLoadedMode = mode
                viewModel.loadLibrary()
            }
        } else {
            lastLoadedMode = AccessMode.NONE
        }
        render(viewModel.state.value)
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

    private fun setupGrid() {
        adapter = GalleryAdapter { item ->
            val items = viewModel.state.value.filteredItems
            val index = items.indexOfFirst { it.uri == item.uri }
            if (index >= 0) {
                viewModel.openLibrary(index)
            }
        }

        val spanCount = resources.getInteger(R.integer.grid_span_count)
        val layoutManager = GridLayoutManager(this, spanCount)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (adapter.getItemViewType(position) == GalleryAdapter.TYPE_HEADER) {
                    spanCount
                } else {
                    1
                }
            }
        }
        binding.grid.layoutManager = layoutManager
        binding.grid.adapter = adapter
        binding.grid.setHasFixedSize(true)
    }

    private fun setupActions() {
        binding.tabAll.setOnClickListener { selectFilter(MediaFilter.ALL) }
        binding.tabPhotos.setOnClickListener { selectFilter(MediaFilter.PHOTOS) }
        binding.tabVideos.setOnClickListener { selectFilter(MediaFilter.VIDEOS) }

        binding.refresh.setOnClickListener {
            if (accessMode() == AccessMode.NONE) {
                requestMediaPermissions()
            } else {
                viewModel.loadLibrary()
            }
        }

        binding.partialManage.setOnClickListener {
            requestMediaPermissions()
        }

        binding.swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(this, R.color.text_primary)
        )
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(this, R.color.surface_high)
        )
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.loadLibrary()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        render(state)
                    }
                }
                launch {
                    viewModel.openViewer.collect { request ->
                        openViewer(request)
                    }
                }
                launch {
                    viewModel.messages.collect { message ->
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun render(state: GalleryUiState) {
        val mode = accessMode()
        updateTabs(state.filter)
        binding.refresh.isVisible = mode != AccessMode.NONE
        binding.filterBar.isVisible = mode != AccessMode.NONE

        if (mode == AccessMode.NONE) {
            binding.subtitle.text = getString(R.string.subtitle_empty)
            binding.partialBanner.isVisible = false
            binding.swipeRefresh.isVisible = false
            binding.loading.isVisible = false
            showPermissionStatus()
            return
        }

        binding.subtitle.text = if (state.loading && state.items.isEmpty() && !state.loaded) {
            getString(R.string.subtitle_loading)
        } else {
            getString(
                R.string.subtitle_counts,
                Format.count(state.photoCount),
                Format.count(state.videoCount)
            )
        }

        if (!state.loaded) {
            binding.statusPanel.isVisible = false
            binding.swipeRefresh.isVisible = false
            binding.partialBanner.isVisible = false
            binding.loading.isVisible = true
            return
        }

        binding.loading.isVisible = false

        val error = state.error
        if (error != null && state.items.isEmpty()) {
            binding.swipeRefresh.isVisible = false
            binding.partialBanner.isVisible = false
            showStatus(
                title = getString(R.string.error_title),
                message = error,
                primaryText = getString(R.string.retry),
                onPrimary = { viewModel.loadLibrary() },
                secondaryText = getString(R.string.use_picker),
                onSecondary = { launchPicker() }
            )
            return
        }

        if (error != null && !errorToastShown) {
            errorToastShown = true
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            viewModel.consumeError()
        } else if (error == null) {
            errorToastShown = false
        }

        val items = state.filteredItems
        if (items.isEmpty()) {
            binding.swipeRefresh.isVisible = false
            binding.partialBanner.isVisible = mode == AccessMode.PARTIAL && state.items.isNotEmpty()
            val message = when {
                mode == AccessMode.PARTIAL -> getString(R.string.empty_partial_message)
                state.filter == MediaFilter.PHOTOS -> getString(R.string.empty_photos)
                state.filter == MediaFilter.VIDEOS -> getString(R.string.empty_videos)
                else -> getString(R.string.empty_message)
            }
            val primaryText = when {
                state.items.isNotEmpty() -> getString(R.string.view_all)
                mode == AccessMode.PARTIAL -> getString(R.string.partial_manage)
                else -> getString(R.string.refresh)
            }
            showStatus(
                title = getString(R.string.empty_title),
                message = message,
                primaryText = primaryText,
                onPrimary = {
                    when {
                        state.items.isNotEmpty() -> selectFilter(MediaFilter.ALL)
                        mode == AccessMode.PARTIAL -> requestMediaPermissions()
                        else -> viewModel.loadLibrary()
                    }
                },
                secondaryText = getString(R.string.use_picker),
                onSecondary = { launchPicker() }
            )
            return
        }

        binding.statusPanel.isVisible = false
        binding.swipeRefresh.isVisible = true
        binding.swipeRefresh.isRefreshing = state.loading
        binding.partialBanner.isVisible = mode == AccessMode.PARTIAL
        adapter.submitList(buildEntries(items))
    }

    private fun updateTabs(filter: MediaFilter) {
        binding.tabAll.isSelected = filter == MediaFilter.ALL
        binding.tabPhotos.isSelected = filter == MediaFilter.PHOTOS
        binding.tabVideos.isSelected = filter == MediaFilter.VIDEOS
    }

    private fun selectFilter(filter: MediaFilter) {
        viewModel.setFilter(filter)
        binding.grid.scrollToPosition(0)
    }

    private fun buildEntries(items: List<MediaItem>): List<GalleryEntry> {
        val grouped = LinkedHashMap<LocalDate, MutableList<MediaItem>>()
        items.forEach { item ->
            grouped.getOrPut(Format.dayKey(item.dateMillis)) { mutableListOf() }.add(item)
        }
        return buildList {
            grouped.forEach { (date, list) ->
                add(
                    GalleryEntry.Header(
                        date = date,
                        label = Format.dayLabel(this@MainActivity, date),
                        count = list.size
                    )
                )
                list.forEach { add(GalleryEntry.Item(it)) }
            }
        }
    }

    private fun showPermissionStatus() {
        if (permissionPermanentlyDenied) {
            showStatus(
                title = getString(R.string.permission_denied_title),
                message = getString(R.string.permission_denied_message),
                primaryText = getString(R.string.permission_settings),
                onPrimary = { openAppSettings() },
                secondaryText = getString(R.string.use_picker),
                onSecondary = { launchPicker() }
            )
        } else {
            showStatus(
                title = getString(R.string.permission_title),
                message = getString(R.string.permission_message),
                primaryText = getString(R.string.permission_allow),
                onPrimary = { requestMediaPermissions() },
                secondaryText = getString(R.string.use_picker),
                onSecondary = { launchPicker() }
            )
        }
    }

    private fun showStatus(
        title: String,
        message: String,
        primaryText: String,
        onPrimary: () -> Unit,
        secondaryText: String? = null,
        onSecondary: (() -> Unit)? = null
    ) {
        binding.statusPanel.isVisible = true
        binding.statusTitle.text = title
        binding.statusMessage.text = message
        binding.statusPrimary.text = primaryText
        binding.statusPrimary.setOnClickListener { onPrimary() }

        if (secondaryText != null && onSecondary != null) {
            binding.statusSecondary.isVisible = true
            binding.statusSecondary.text = secondaryText
            binding.statusSecondary.setOnClickListener { onSecondary() }
        } else {
            binding.statusSecondary.isVisible = false
            binding.statusSecondary.setOnClickListener(null)
        }
        binding.loading.isVisible = false
    }

    private fun openViewer(request: ViewerRequest) {
        when (request) {
            is ViewerRequest.Library -> {
                MediaSession.items = request.items
                startActivity(ViewerActivity.intent(this, request.index))
            }

            is ViewerRequest.Picked -> {
                MediaSession.items = request.items
                startActivity(ViewerActivity.intent(this, 0))
            }
        }
    }

    private fun requestMediaPermissions() {
        permissionLauncher.launch(requiredPermissions())
    }

    private fun launchPicker() {
        pickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
        )
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:$packageName".toUri()
        )
        runCatching { startActivity(intent) }
    }

    private fun requiredPermissions(): Array<String> {
        return when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )

            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )

            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun accessMode(): AccessMode {
        if (Build.VERSION.SDK_INT >= 33) {
            val images = checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) ==
                PackageManager.PERMISSION_GRANTED
            val videos = checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) ==
                PackageManager.PERMISSION_GRANTED
            if (images && videos) return AccessMode.FULL
        } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return AccessMode.FULL
        }

        if (Build.VERSION.SDK_INT >= 34 &&
            checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return AccessMode.PARTIAL
        }
        return AccessMode.NONE
    }
}
