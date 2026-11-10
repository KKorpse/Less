package com.kkorpse.less.prefs

import android.content.Context

enum class DoubleTapAction {
    ZOOM, FAVORITE
}

enum class BrowseMode {
    RANDOM, SEQUENTIAL
}

class AppPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("less_settings", Context.MODE_PRIVATE)

    var groupSize: Int
        get() = prefs.getInt(KEY_GROUP_SIZE, DEFAULT_GROUP_SIZE)
            .coerceIn(MIN_GROUP_SIZE, MAX_GROUP_SIZE)
        set(value) {
            prefs.edit()
                .putInt(KEY_GROUP_SIZE, value.coerceIn(MIN_GROUP_SIZE, MAX_GROUP_SIZE))
                .apply()
        }

    var doubleTapAction: DoubleTapAction
        get() = runCatching {
            DoubleTapAction.valueOf(
                prefs.getString(KEY_DOUBLE_TAP, DoubleTapAction.ZOOM.name)
                    ?: DoubleTapAction.ZOOM.name
            )
        }.getOrDefault(DoubleTapAction.ZOOM)
        set(value) {
            prefs.edit().putString(KEY_DOUBLE_TAP, value.name).apply()
        }

    var browseMode: BrowseMode
        get() = runCatching {
            BrowseMode.valueOf(
                prefs.getString(KEY_BROWSE_MODE, BrowseMode.SEQUENTIAL.name)
                    ?: BrowseMode.SEQUENTIAL.name
            )
        }.getOrDefault(BrowseMode.SEQUENTIAL)
        set(value) {
            prefs.edit().putString(KEY_BROWSE_MODE, value.name).apply()
        }

    companion object {
        const val MIN_GROUP_SIZE = 3
        const val MAX_GROUP_SIZE = 30
        const val DEFAULT_GROUP_SIZE = 10

        private const val KEY_GROUP_SIZE = "group_size"
        private const val KEY_DOUBLE_TAP = "double_tap"
        private const val KEY_BROWSE_MODE = "browse_mode"
    }
}
