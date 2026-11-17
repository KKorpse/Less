package com.kkorpse.less

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.kkorpse.less.prefs.BrowseStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowseStoreViewedTest {

    private fun uri(id: Long): Uri =
        Uri.parse("content://media/external/images/media/$id")

    @Test
    fun markViewed_isVisibleInSameInstance() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = BrowseStore(context)
        store.markViewed(uri(1), false)
        assertTrue(store.isViewed(uri(1), false))
        assertEquals(setOf(uri(1).toString()), store.viewedUris(false))
    }

    @Test
    fun markViewed_persistsAcrossInstances() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        BrowseStore(context).markViewed(uri(2), false)
        val reopened = BrowseStore(context)
        assertEquals(setOf(uri(2).toString()), reopened.viewedUris(false))
    }

    @Test
    fun markViewed_countsOnlyNewItems() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = BrowseStore(context)
        store.markViewed(uri(3), false)
        store.markViewed(uri(3), false)
        assertEquals(1, store.viewedUris(false).size)
    }
}
