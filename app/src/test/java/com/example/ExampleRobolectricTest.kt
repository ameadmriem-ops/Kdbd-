package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun readAppNameStringFromContext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Black Anime", appName)
    }

    @Test
    fun testAdMobSharedPreferencesPersistence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(MainActivity.PREFS_ADMOB, Context.MODE_PRIVATE)

        // Clear existing prefs
        prefs.edit().clear().commit()

        // Verify initial state
        var count = prefs.getInt(MainActivity.KEY_FIVE_ADS_COUNT, 0)
        var exemptUntil = prefs.getLong(MainActivity.KEY_EXEMPT_UNTIL, 0L)
        assertEquals(0, count)
        assertFalse(exemptUntil > System.currentTimeMillis())

        // Simulate 3 rewarded ads completed
        for (i in 1..3) {
            count++
            prefs.edit().putInt(MainActivity.KEY_FIVE_ADS_COUNT, count).commit()
        }
        assertEquals(3, prefs.getInt(MainActivity.KEY_FIVE_ADS_COUNT, 0))

        // Complete remaining 2 ads and activate 24-hour exemption
        val now = System.currentTimeMillis()
        val expectedExpiry = now + MainActivity.ONE_DAY_MS
        prefs.edit()
            .putLong(MainActivity.KEY_EXEMPT_UNTIL, expectedExpiry)
            .putInt(MainActivity.KEY_FIVE_ADS_COUNT, 0)
            .commit()

        val savedExpiry = prefs.getLong(MainActivity.KEY_EXEMPT_UNTIL, 0L)
        val savedCountAfterExempt = prefs.getInt(MainActivity.KEY_FIVE_ADS_COUNT, 0)

        assertEquals(0, savedCountAfterExempt)
        assertTrue(savedExpiry > System.currentTimeMillis())
        assertTrue(savedExpiry >= expectedExpiry)
    }
}
