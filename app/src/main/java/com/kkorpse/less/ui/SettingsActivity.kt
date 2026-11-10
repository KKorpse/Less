package com.kkorpse.less.ui

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.kkorpse.less.LessApp
import com.kkorpse.less.R
import com.kkorpse.less.databinding.ActivitySettingsBinding
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

        binding.groupSizeSlider.value = preferences.groupSize.toFloat().coerceIn(3f, 30f)
        updateGroupSizeLabel(preferences.groupSize)
        binding.groupSizeSlider.addOnChangeListener { _, value, _ ->
            val size = value.roundToInt().coerceIn(3, 30)
            preferences.groupSize = size
            updateGroupSizeLabel(size)
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
            preferences.browseMode = when (checkedId) {
                R.id.modeRandom -> BrowseMode.RANDOM
                else -> BrowseMode.SEQUENTIAL
            }
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
}
