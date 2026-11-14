package com.kkorpse.less

import android.Manifest
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchTest {

    private fun grantMediaPermissions(app: LessApp) {
        shadowOf(app).grantPermissions(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
    }

    @Test
    fun launchWithPermissions() {
        val app = ApplicationProvider.getApplicationContext<LessApp>()
        grantMediaPermissions(app)
        Robolectric.buildActivity(com.kkorpse.less.ui.MainActivity::class.java)
            .setup()
            .get()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun switchToVideoAndStatsTabs() {
        val app = ApplicationProvider.getApplicationContext<LessApp>()
        grantMediaPermissions(app)
        val activity = Robolectric.buildActivity(com.kkorpse.less.ui.MainActivity::class.java)
            .setup()
            .get()
        shadowOf(Looper.getMainLooper()).idle()

        activity.findViewById<View>(R.id.navVideos).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        activity.findViewById<View>(R.id.navStats).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }
}
