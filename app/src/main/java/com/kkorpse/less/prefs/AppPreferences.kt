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

    /** 展开的宽度：随机模式下「展开」时左右各补进多少张。 */
    var expandRadius: Int
        get() = prefs.getInt(KEY_EXPAND_RADIUS, DEFAULT_EXPAND_RADIUS)
            .coerceIn(MIN_EXPAND_RADIUS, MAX_EXPAND_RADIUS)
        set(value) {
            prefs.edit()
                .putInt(
                    KEY_EXPAND_RADIUS,
                    value.coerceIn(MIN_EXPAND_RADIUS, MAX_EXPAND_RADIUS)
                )
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

    /** 照片专注里上次看到的组与位置，用于退出再进来时续看。 */
    var photoGroupKey: String?
        get() = prefs.getString(KEY_PHOTO_GROUP_KEY, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_PHOTO_GROUP_KEY)
                else putString(KEY_PHOTO_GROUP_KEY, value)
            }.apply()
        }

    var photoGroupIndex: Int
        get() = prefs.getInt(KEY_PHOTO_GROUP_INDEX, 0)
        set(value) {
            prefs.edit().putInt(KEY_PHOTO_GROUP_INDEX, value.coerceAtLeast(0)).apply()
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
        const val MIN_EXPAND_RADIUS = 1
        const val MAX_EXPAND_RADIUS = 10
        const val DEFAULT_EXPAND_RADIUS = 4

        private const val KEY_GROUP_SIZE = "group_size"
        private const val KEY_EXPAND_RADIUS = "expand_radius"
        private const val KEY_DOUBLE_TAP = "double_tap"
        private const val KEY_BROWSE_MODE = "browse_mode"
        private const val KEY_LOCATION_ASKED = "location_asked"
        private const val KEY_VIDEO_GROUP_SIZE = "video_group_size"
        private const val KEY_PHOTO_GROUP_KEY = "photo_group_key"
        private const val KEY_PHOTO_GROUP_INDEX = "photo_group_index"
        private const val KEY_PHOTO_BUCKET_ID = "photo_bucket_id"
        private const val KEY_PHOTO_BUCKET_NAME = "photo_bucket_name"
    }
}
