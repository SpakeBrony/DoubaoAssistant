package com.spake.brony.doubaoassistant.navigation

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NavigationLauncherTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val pm get() = shadowOf(app.packageManager)
    private val launcher get() = NavigationLauncher(app)

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun install(pkg: String, action: String, scheme: String, exported: Boolean = true) {
        val component = ComponentName(pkg, "$pkg.MapActivity")
        pm.addActivityIfNotPresent(component).apply { this.exported = exported; enabled = true }
        pm.addIntentFilterForActivity(component, IntentFilter(action).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataScheme(scheme)
        })
    }

    @Test fun defaultMapWinsOverOtherNavigationProvidersAndChineseNameIsEncoded() = runTest {
        install("com.autonavi.minimap", Intent.ACTION_VIEW, "geo")
        install("com.autonavi.minimap", Intent.ACTION_VIEW, "androidamap")
        install("other.map", "android.intent.action.NAVIGATE", "geo")
        assertTrue(launcher.navigate("杭州东站 & 西广场?出口#1"))
        val intent = shadowOf(app).nextStartedActivity
        assertEquals("com.autonavi.minimap", intent.`package`)
        assertEquals("androidamap", intent.data?.scheme)
        assertEquals("keywordNavi", intent.data?.host)
        assertEquals("杭州东站 & 西广场?出口#1", intent.data?.getQueryParameter("keyword"))
        assertEquals(app.packageName, intent.data?.getQueryParameter("sourceApplication"))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun actualPreferredMapIsUsedEvenWhenSeveralMapsAreInstalled() = runTest {
        install("first.map", Intent.ACTION_VIEW, "geo")
        install("preferred.map", Intent.ACTION_VIEW, "geo")
        val filter = IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT); addDataScheme("geo")
        }
        val preferred = ComponentName("preferred.map", "preferred.map.MapActivity")
        app.packageManager.addPreferredActivity(filter, IntentFilter.MATCH_CATEGORY_SCHEME,
            arrayOf(ComponentName("first.map", "first.map.MapActivity"), preferred), preferred)
        assertTrue(launcher.navigate("上海虹桥机场"))
        assertEquals("preferred.map", shadowOf(app).nextStartedActivity.`package`)
    }

    @Test fun multipleMapsWithoutDefaultShowOneEntryPerAppUsingTheirBestSupportedIntent() = runTest {
        install("first.map", Intent.ACTION_VIEW, "geo")
        install("first.map", "android.intent.action.NAVIGATE", "geo")
        install("second.map", Intent.ACTION_VIEW, "geo")
        pm.setShouldShowActivityChooser(true)
        assertTrue(launcher.navigate("西湖"))
        val chooser = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val primary = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        val additional = chooser.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS)!!.map { it as Intent }
        val entries = listOf(primary) + additional
        assertEquals(setOf("first.map", "second.map"), entries.map { it.`package` }.toSet())
        assertEquals(2, entries.size)
        assertEquals("android.intent.action.NAVIGATE", entries.first { it.`package` == "first.map" }.action)
        assertTrue(entries.all { Uri.decode(it.data!!.encodedSchemeSpecificPart).contains("西湖") })
    }

    @Test fun standardNavigationWorksWithoutGeoViewHandlerOnPhoneAndCar() = runTest {
        for ((car, action) in listOf(false to "android.intent.action.NAVIGATE", true to "androidx.car.app.action.NAVIGATE")) {
            pm.setSystemFeature(PackageManager.FEATURE_AUTOMOTIVE, car)
            install("native.map", action, "geo")
            assertTrue(launcher.navigate("杭州东站"))
            val intent = shadowOf(app).nextStartedActivity
            assertEquals(action, intent.action)
            assertEquals("native.map", intent.`package`)
            assertTrue(intent.data.toString().contains("intent=navigation"))
            pm.clearIntentFilterForActivity(ComponentName("native.map", "native.map.MapActivity"))
        }
    }

    @Test fun googleNavigationSchemeIsUsedWhenThatIsTheDefaultMapsSupportedEntry() = runTest {
        install("com.google.android.apps.maps", Intent.ACTION_VIEW, "geo")
        install("com.google.android.apps.maps", Intent.ACTION_VIEW, "google.navigation")
        assertTrue(launcher.navigate("1st & Pike, Seattle"))
        val intent = shadowOf(app).nextStartedActivity
        assertEquals("google.navigation", intent.data?.scheme)
        assertEquals("q=1st & Pike, Seattle&mode=d", Uri.decode(intent.data!!.encodedSchemeSpecificPart))
    }

    @Test fun unknownDefaultMapFallsBackToItsGeoEntryWithoutInventingAProtocol() = runTest {
        install("car.vendor.map", Intent.ACTION_VIEW, "geo")
        assertTrue(launcher.navigate("  上海虹桥机场  "))
        val intent = shadowOf(app).nextStartedActivity
        assertEquals("car.vendor.map", intent.`package`)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("geo", intent.data?.scheme)
        assertEquals("0,0?q=上海虹桥机场&mode=d&intent=navigation", Uri.decode(intent.data!!.encodedSchemeSpecificPart))
    }

    @Test fun noMapOrNonExportedReceiverOrBlankDestinationDoesNotReportSuccess() = runTest {
        assertFalse(launcher.navigate("西湖"))
        install("private.map", Intent.ACTION_VIEW, "geo", exported = false)
        assertFalse(launcher.navigate("西湖"))
        assertFalse(launcher.navigate(" \n "))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun deniedActivityLaunchReturnsFailure() = runTest {
        install("car.vendor.map", Intent.ACTION_VIEW, "geo")
        val denied = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun startActivity(intent: Intent) { throw SecurityException("Launch denied") }
        }
        assertFalse(NavigationLauncher(denied).navigate("西湖"))
        assertNull(shadowOf(app).nextStartedActivity)
    }
}
