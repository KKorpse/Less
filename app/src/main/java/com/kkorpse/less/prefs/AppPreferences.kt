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

    /** 视频每组数量，和照片的每组数量分开设置。 */
    var videoGroupSize: Int
        get() = prefs.getInt(KEY_VIDEO_GROUP_SIZE, DEFAULT_VIDEO_GROUP_SIZE)
            .coerceIn(MIN_GROUP_SIZE, MAX_GROUP_SIZE)
        set(value) {
            prefs.edit()
                .putInt(KEY_VIDEO_GROUP_SIZE, value.coerceIn(MIN_GROUP_SIZE, MAX_GROUP_SIZE))
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

    /** 当前选择的相册（MediaStore BUCKET_ID），null 表示系统相册的全部照片。 */
    var photoBucketId: String?
        get() = prefs.getString(KEY_PHOTO_BUCKET_ID, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_PHOTO_BUCKET_ID) else putString(
                    KEY_PHOTO_BUCKET_ID,
                    value
                )
            }.apply()
        }

    /** 相册显示名，仅用于首页展示，避免每次重新查询。 */
    var photoBucketName: String?
        get() = prefs.getString(KEY_PHOTO_BUCKET_NAME, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_PHOTO_BUCKET_NAME) else putString(
                    KEY_PHOTO_BUCKET_NAME,
                    value
                )
            }.apply()
        }

    var locationAsked: Boolean
        get() = prefs.getBoolean(KEY_LOCATION_ASKED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_LOCATION_ASKED, value).apply()
        }

    companion object {
        const val MIN_GROUP_SIZE = 3
        const val MAX_GROUP_SIZE = 30
        const val DEFAULT_GROUP_SIZE = 10
        const val DEFAULT_VIDEO_GROUP_SIZE = 10

        private const val KEY_GROUP_SIZE = "group_size"
        private const val KEY_DOUBLE_TAP = "double_tap"
        private const val KEY_BROWSE_MODE = "browse_mode"
        private const val KEY_LOCATION_ASKED = "location_asked"
        private const val KEY_VIDEO_GROUP_SIZE = "video_group_size"
        private const val KEY_PHOTO_BUCKET_ID = "photo_bucket_id"
        private const val KEY_PHOTO_BUCKET_NAME = "photo_bucket_name"
    }
}
