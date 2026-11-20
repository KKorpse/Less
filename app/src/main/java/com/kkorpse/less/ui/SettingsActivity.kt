package com.kkorpse.less.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.kkorpse.less.LessApp
import com.kkorpse.less.R
import com.kkorpse.less.databinding.ActivitySettingsBinding
import com.kkorpse.less.prefs.AppPreferences
import com.kkorpse.less.prefs.BrowseMode
import com.kkorpse.less.prefs.DoubleTapAction
import com.kkorpse.less.util.dp
import kotlin.math.roundToInt

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val app = application as LessApp
        val preferences = app.preferences

        setupInsets()
        binding.back.setOnClickListener { finish() }
        binding.liveCheckButton.setOnClickListener {
            val app2 = application as LessApp
            Toast.makeText(this, R.string.settings_live_check_started, Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val repo = com.kkorpse.less.data.MediaRepository(
                    this@SettingsActivity,
                    app2.browseStore
                )
                val items = repo.loadEverything().getOrElse { emptyList() }
                val report = com.kkorpse.less.data.LivePhotoDetector.diagnose(
                    this@SettingsActivity,
                    items
                )
                val version = runCatching {
                    packageManager.getPackageInfo(packageName, 0).versionName
                }.getOrNull().orEmpty()
                val header = "app: $version\n"
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Less live check", header + report))
                Toast.makeText(
                    this@SettingsActivity,
                    R.string.settings_live_check_done,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        binding.diagnosticsButton.setOnClickListener {
            val text = com.kkorpse.less.Diagnostics.report(this)
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Less diagnostics", text))
            Toast.makeText(this, R.string.settings_diagnostics_copied, Toast.LENGTH_SHORT).show()
        }

        binding.groupSizeSlider.value = preferences.groupSize.toFloat().coerceIn(3f, 30f)
        updateGroupSizeLabel(preferences.groupSize)
        binding.groupSizeSlider.addOnChangeListener { _, value, _ ->
            val size = value.roundToInt().coerceIn(3, 30)
            preferences.groupSize = size
            updateGroupSizeLabel(size)
        }

        binding.expandWidthSlider.value = preferences.expandRadius.toFloat()
            .coerceIn(
                AppPreferences.MIN_EXPAND_RADIUS.toFloat(),
                AppPreferences.MAX_EXPAND_RADIUS.toFloat()
            )
        updateExpandWidthLabel(preferences.expandRadius)
        binding.expandWidthSlider.addOnChangeListener { _, value, _ ->
            val radius = value.roundToInt().coerceIn(
                AppPreferences.MIN_EXPAND_RADIUS,
                AppPreferences.MAX_EXPAND_RADIUS
            )
            preferences.expandRadius = radius
            updateExpandWidthLabel(radius)
        }
        // 展开只在随机模式下有意义，设置项跟着模式显隐。
        binding.expandWidthCard.isVisible = preferences.browseMode == BrowseMode.RANDOM

        binding.favoritesOnlySwitch.isChecked = preferences.favoritesOnly
        binding.favoritesOnlySwitch.setOnCheckedChangeListener { _, checked ->
            preferences.favoritesOnly = checked
        }

        binding.videoGroupSizeSlider.value = preferences.videoGroupSize.toFloat().coerceIn(3f, 30f)
        updateVideoGroupSizeLabel(preferences.videoGroupSize)
        binding.videoGroupSizeSlider.addOnChangeListener { _, value, _ ->
            val size = value.roundToInt().coerceIn(3, 30)
            preferences.videoGroupSize = size
            updateVideoGroupSizeLabel(size)
        }

        if (preferences.doubleTapAction == DoubleTapAction.FAVORITE) {
            binding.doubleTapGroup.check(R.id.doubleTapFavorite)
        } else {
            binding.doubleTapGroup.check(R.id.doubleTapZoom)
        }
        binding.doubleTapGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            preferences.doubleTapAction = when (checkedId) {
                R.id.doubleTapFavorite -> DoubleTapAction.FAVORITE
                else -> DoubleTapAction.ZOOM
            }
        }

        if (preferences.browseMode == BrowseMode.RANDOM) {
            binding.browseModeGroup.check(R.id.modeRandom)
        } else {
            binding.browseModeGroup.check(R.id.modeSequential)
        }
        binding.browseModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val target = when (checkedId) {
                R.id.modeRandom -> BrowseMode.RANDOM
                else -> BrowseMode.SEQUENTIAL
            }
            val previous = preferences.browseMode
            if (previous == target) return@addOnButtonCheckedListener
            preferences.browseMode = target
            // 只有「顺序 -> 随机」才换一组；随机 -> 顺序 要回到原来那一组。
            if (previous == BrowseMode.SEQUENTIAL && target == BrowseMode.RANDOM) {
                app.browseStore.clearCurrentGroup(preferences.groupKeyFor(BrowseMode.RANDOM))
            }
            binding.expandWidthCard.isVisible = target == BrowseMode.RANDOM
        }

        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()
        binding.version.text = getString(R.string.settings_version, versionName)
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

    private fun updateGroupSizeLabel(size: Int) {
        binding.groupSizeValue.text = getString(R.string.settings_group_size_value, size)
    }

    private fun updateExpandWidthLabel(radius: Int) {
        binding.expandWidthValue.text = getString(R.string.settings_expand_width_value, radius)
    }

    private fun updateVideoGroupSizeLabel(size: Int) {
        binding.videoGroupSizeValue.text = getString(R.string.settings_group_size_value, size)
    }
}
