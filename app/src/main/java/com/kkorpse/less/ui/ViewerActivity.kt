package com.kkorpse.less.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.viewpager2.widget.ViewPager2
import com.kkorpse.less.R
import com.kkorpse.less.data.MediaItem
import com.kkorpse.less.data.MediaSession
import com.kkorpse.less.databinding.ActivityViewerBinding
import com.kkorpse.less.util.Format
import com.kkorpse.less.util.dp

class ViewerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_INDEX = "extra_index"
        private const val STATE_INDEX = "state_index"
        private const val CHROME_TIMEOUT_MS = 3500L

        fun intent(context: Context, index: Int): Intent {
            return Intent(context, ViewerActivity::class.java)
                .putExtra(EXTRA_INDEX, index)
        }
    }

    private lateinit var binding: ActivityViewerBinding
    private lateinit var adapter: ViewerAdapter
    private lateinit var items: List<MediaItem>

    private var chromeVisible = true
    private var currentItemIsVideo = false
    private val handler = Handler(Looper.getMainLooper())
    private val hideChromeRunnable = Runnable { setChromeVisible(false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        items = MediaSession.items
        if (items.isEmpty()) {
            // 进程被系统回收后浏览会话会丢失，回到图库而不是直接退出应用。
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setupInsets()
        setupPager(savedInstanceState)

        binding.back.setOnClickListener { finish() }
        scheduleHide()
    }

    override fun onResume() {
        super.onResume()
        if (!chromeVisible) {
            setChromeVisible(true)
        } else {
            scheduleHide()
        }
    }

    override fun onPause() {
        if (::adapter.isInitialized) {
            adapter.pauseAll()
        }
        handler.removeCallbacks(hideChromeRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideChromeRunnable)
        if (::adapter.isInitialized) {
            adapter.releaseAll()
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::binding.isInitialized) {
            outState.putInt(STATE_INDEX, binding.pager.currentItem)
        }
    }

    private fun setupInsets() {
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.topBar.updatePadding(top = dp(6) + bars.top)
            binding.bottomBar.updatePadding(bottom = dp(20) + bars.bottom)
            insets
        }
    }

    private fun setupPager(savedInstanceState: Bundle?) {
        val initial = (savedInstanceState?.getInt(STATE_INDEX)
            ?: intent.getIntExtra(EXTRA_INDEX, 0)).coerceIn(items.indices)

        adapter = ViewerAdapter(
            items = items,
            onImageTap = { toggleChrome() },
            onVideoControllerVisibilityChanged = { position, visible ->
                if (position == binding.pager.currentItem) {
                    setChromeVisible(visible)
                }
            }
        )
        adapter.currentPosition = initial

        binding.pager.adapter = adapter
        binding.pager.offscreenPageLimit = 1
        binding.pager.setCurrentItem(initial, false)

        updateChrome(initial)
        handler.postDelayed(hideChromeRunnable, CHROME_TIMEOUT_MS)
        binding.pager.post {
            if (::adapter.isInitialized) {
                adapter.showController(initial)
            }
        }

        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                adapter.currentPosition = position
                adapter.pauseAll()
                setChromeVisible(true)
                updateChrome(position)
                binding.pager.post { adapter.showController(position) }
                scheduleHide()
            }
        })
    }

    private fun updateChrome(position: Int) {
        val item = items.getOrNull(position) ?: return
        currentItemIsVideo = item.isVideo
        binding.counter.text = getString(R.string.viewer_position, position + 1, items.size)
        binding.fileName.text = item.name
        binding.topFileName.isVisible = item.isVideo
        binding.topFileName.text = item.name
        // 视频页把底部空间留给 ExoPlayer 自带的播放控件
        binding.bottomBar.isVisible = chromeVisible && !item.isVideo

        val parts = ArrayList<String>(4)
        parts += Format.fullDate(item.dateMillis)
        if (item.width > 0 && item.height > 0) {
            parts += "${item.width} × ${item.height}"
        }
        if (item.sizeBytes > 0L) {
            val size = Format.fileSize(item.sizeBytes)
            if (size.isNotEmpty()) parts += size
        }
        if (item.isVideo && item.durationMs > 0L) {
            parts += Format.duration(item.durationMs)
        }
        binding.meta.text = parts.joinToString(" · ")
    }

    private fun toggleChrome() {
        setChromeVisible(!chromeVisible)
    }

    private fun setChromeVisible(visible: Boolean) {
        if (chromeVisible == visible) return
        chromeVisible = visible

        if (visible) {
            binding.topBar.isVisible = true
            binding.bottomBar.isVisible = !currentItemIsVideo
            binding.topBar.animate().alpha(1f).setDuration(180).start()
            binding.bottomBar.animate().alpha(1f).setDuration(180).start()
            WindowCompat.getInsetsController(window, binding.root).show(
                WindowInsetsCompat.Type.systemBars()
            )
            scheduleHide()
        } else {
            binding.topBar.animate().alpha(0f).setDuration(180).withEndAction {
                if (!chromeVisible) {
                    binding.topBar.visibility = View.GONE
                    binding.bottomBar.visibility = View.GONE
                }
            }.start()
            binding.bottomBar.animate().alpha(0f).setDuration(180).start()
            WindowCompat.getInsetsController(window, binding.root).hide(
                WindowInsetsCompat.Type.systemBars()
            )
            adapter.hideControllers()
            handler.removeCallbacks(hideChromeRunnable)
        }
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideChromeRunnable)
        handler.postDelayed(hideChromeRunnable, CHROME_TIMEOUT_MS)
    }
}
