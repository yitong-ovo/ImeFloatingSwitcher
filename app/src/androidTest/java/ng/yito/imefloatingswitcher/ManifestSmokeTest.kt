package ng.yito.imefloatingswitcher

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManifestSmokeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun doesNotDeclareAccessibilityService() {
        val services = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_SERVICES)
            .services
            .orEmpty()
        assertFalse(services.any { it.permission == "android.permission.BIND_ACCESSIBILITY_SERVICE" })
    }

    @Test
    fun declaresWatcherAsSpecialUseForegroundService() {
        val watcher = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_SERVICES)
            .services
            .orEmpty()
            .firstOrNull { it.name.endsWith("ImeWatcherService") }
        assertTrue(watcher != null)
        assertTrue(watcher!!.foregroundServiceType != 0)
    }
}
