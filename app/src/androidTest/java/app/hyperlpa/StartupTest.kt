package app.hyperlpa

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupTest {
    @Test
    fun savedPredictiveBackDrawsWithoutStartupRecreation() {
        checkStartup(predictiveBack = true)
    }

    @Test
    fun disabledPredictiveBackDrawsAfterActivityRecreation() {
        checkStartup(predictiveBack = false, recreate = true)
    }

    private fun checkStartup(predictiveBack: Boolean, recreate: Boolean = false) {
        val application = ApplicationProvider.getApplicationContext<HyperLpaApplication>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val originalSetting = runBlocking { application.settingsStore.settings.first().predictiveBack }
        val originalRuntimeFlag = application.isPredictiveBackEnabled()
        val created = AtomicInteger()
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (activity is MainActivity) created.incrementAndGet()
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        try {
            runBlocking { application.settingsStore.setPredictiveBack(predictiveBack) }
            instrumentation.runOnMainSync {
                // Match Application.onCreate's cached runtime flag on a real cold launch.
                application.setPredictiveBackEnabled(predictiveBack)
                application.registerActivityLifecycleCallbacks(callbacks)
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                assertDraws(scenario)
                assertEquals("Saved settings must not cause a startup recreation", 1, created.get())
                if (recreate) {
                    scenario.recreate()
                    assertDraws(scenario)
                    assertEquals(2, created.get())
                }
            }
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
            runBlocking { application.settingsStore.setPredictiveBack(originalSetting) }
            instrumentation.runOnMainSync {
                application.setPredictiveBackEnabled(originalRuntimeFlag)
            }
        }
    }

    private fun assertDraws(scenario: ActivityScenario<MainActivity>) {
        val drawn = CountDownLatch(1)
        scenario.onActivity { activity ->
            val content = activity.findViewById<android.view.View>(android.R.id.content)
            val listener = object : ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    drawn.countDown()
                    content.post { content.viewTreeObserver.removeOnDrawListener(this) }
                }
            }
            content.viewTreeObserver.addOnDrawListener(listener)
            content.invalidate()
        }
        assertTrue("The splash must release and allow app content to draw", drawn.await(10, TimeUnit.SECONDS))
    }
}
