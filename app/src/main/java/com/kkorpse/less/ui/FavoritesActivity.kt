package com.kkorpse.less.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.kkorpse.less.LessApp
import com.kkorpse.less.R
import com.kkorpse.less.data.MediaItem
import com.kkorpse.less.data.MediaRepository
import com.kkorpse.less.data.PhotoFocusSession
import com.kkorpse.less.data.VideoFeedSession
import com.kkorpse.less.databinding.ActivityFavoritesBinding
import com.kkorpse.less.util.Format
import com.kkorpse.less.util.dp
import kotlinx.coroutines.launch
import java.time.LocalDate

class FavoritesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFavoritesBinding
    private lateinit var adapter: GalleryAdapter

    private var favoritePhotos: List<MediaItem> = emptyList()
    private var favoriteVideos: List<MediaItem> = emptyList()

    private val viewerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        loadFavorites()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityFavoritesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setupInsets()
        setupGrid()
        binding.back.setOnClickListener { finish() }
        loadFavorites()
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
            if (item.isVideo) {
                val index = favoriteVideos.indexOfFirst { it.uri == item.uri }
                if (index >= 0) {
                    VideoFeedSession.items = favoriteVideos
                    VideoFeedSession.startIndex = index
                    viewerLauncher.launch(Intent(this, VideoFeedActivity::class.java))
                }
            } else {
                val index = favoritePhotos.indexOfFirst { it.uri == item.uri }
                if (index >= 0) {
                    PhotoFocusSession.items = favoritePhotos
                    PhotoFocusSession.startIndex = index
                    PhotoFocusSession.canTrash = true
                    viewerLauncher.launch(Intent(this, PhotoFocusActivity::class.java))
                }
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

    private fun loadFavorites() {
        lifecycleScope.launch {
            binding.loading.isVisible = true
            val app = application as LessApp
            val favorites = app.browseStore.favoriteUris()
            val items = MediaRepository(application)
                .loadLibrary()
                .getOrElse { emptyList() }
                .filter { it.uri.toString() in favorites }

            favoritePhotos = items.filter { !it.isVideo }
            favoriteVideos = items.filter { it.isVideo }
            binding.subtitle.text = getString(R.string.favorites_count, items.size)

            binding.loading.isVisible = false
            binding.emptyPanel.isVisible = items.isEmpty()
            binding.grid.isVisible = items.isNotEmpty()
            adapter.submitList(buildEntries(items))
        }
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
                        label = Format.dayLabel(this@FavoritesActivity, date),
                        count = list.size
                    )
                )
                list.forEach { add(GalleryEntry.Item(it)) }
            }
        }
    }
}
