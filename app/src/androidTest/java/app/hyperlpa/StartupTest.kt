package app.hyperlpa

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.data.settings.FloatingBottomBarStyle
import app.hyperlpa.data.settings.NavigationStyle
import app.hyperlpa.data.settings.ThemeMode
import app.hyperlpa.ui.HyperLpaViewModel
import app.hyperlpa.ui.navigation.AppRoute
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupTest {
    @Test
    fun savedAppearanceIsPresentBeforeUiFlowCollection() {
        val settings = AppSettings(
            themeMode = ThemeMode.DARK,
            navigationStyle = NavigationStyle.FLOATING,
            floatingBottomBarStyle = FloatingBottomBarStyle.IOS_LIKE,
            densityScale = 0.9f,
            compatibilityWizardCompleted = true,
        )
        withInitialSettings(settings) { viewModel ->
            assertTrue(viewModel.state.value.settingsLoaded)
            assertFalse(viewModel.state.value.initialDataLoaded)
            assertEquals(settings, viewModel.state.value.settings)
            viewModel.resolveStartRoute(settings)
            assertEquals(listOf(AppRoute.Shell), viewModel.navigationBackStack.toList())
        }
    }

    @Test
    fun incompleteFirstRunIsChosenBeforeCompositionAndSurvivesRestore() {
        val settings = AppSettings(compatibilityWizardCompleted = false)
        withInitialSettings(settings) { viewModel ->
            viewModel.resolveStartRoute(settings)
            assertEquals(listOf(AppRoute.FirstRunSetup), viewModel.navigationBackStack.toList())
            val snapshot = viewModel.navigationSnapshot()
            viewModel.restoreNavigation(snapshot)
            viewModel.resolveStartRoute(settings)
            assertEquals(listOf(AppRoute.FirstRunSetup), viewModel.navigationBackStack.toList())

            viewModel.finishFirstRunSetup()
            viewModel.completeFirstRunHandoff()
            // Recreation must not reopen setup while its completion write is pending.
            viewModel.resolveStartRoute(settings)
            assertEquals(listOf(AppRoute.Shell), viewModel.navigationBackStack.toList())
        }
    }

    @Test
    fun activationIntentKeepsDownloadAsTheStartPage() {
        val settings = AppSettings(compatibilityWizardCompleted = false)
        withInitialSettings(settings) { viewModel ->
            assertTrue(viewModel.handleActivationCode("LPA:1\$example.org\$test-code"))
            viewModel.resolveStartRoute(settings)
            assertEquals(
                listOf(AppRoute.Shell, AppRoute.DownloadProfile),
                viewModel.navigationBackStack.toList(),
            )
        }
    }

    @Test
    fun savedPredictiveBackDrawsWithoutStartupRecreation() {
        checkStartup(predictiveBack = true, cachedPredictiveBack = false)
    }

    @Test
    fun disabledPredictiveBackDrawsAfterActivityRecreation() {
        checkStartup(predictiveBack = false, recreate = true)
    }

    private fun withInitialSettings(settings: AppSettings, check: (HyperLpaViewModel) -> Unit) {
        val application = ApplicationProvider.getApplicationContext<HyperLpaApplication>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val store = ViewModelStore()
            val factory = HyperLpaViewModel.Factory(
                application = application,
                settingsStore = application.settingsStore,
                metadataStore = application.metadataStore,
                repository = application.lpaRepository,
                cloudService = application.cloudService,
                notificationHistoryStore = application.notificationHistoryStore,
                supportReportBuilder = application.supportReportBuilder,
                provisioningCoordinator = application.provisioningCoordinator,
                initialSettings = settings,
            )
            val viewModel = ViewModelProvider(store, factory)[HyperLpaViewModel::class.java]
            try {
                check(viewModel)
            } finally {
                store.clear()
            }
        }
    }

    private fun checkStartup(
        predictiveBack: Boolean,
        recreate: Boolean = false,
        cachedPredictiveBack: Boolean = predictiveBack,
    ) {
        val application = ApplicationProvider.getApplicationContext<HyperLpaApplication>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val originalSettings = runBlocking { application.settingsStore.settings.first() }
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
            // Exercise lifecycle and saved flags independently of emulator glass-shader stalls.
            // LiquidNavigationBarTest covers the hardware-rendered glass first draw separately.
            runBlocking { application.settingsStore.setBlurEnabled(false) }
            runBlocking { application.settingsStore.setPredictiveBack(predictiveBack) }
            instrumentation.runOnMainSync {
                // A previous process can leave this cache behind the saved preference.
                application.setPredictiveBackEnabled(cachedPredictiveBack)
                application.registerActivityLifecycleCallbacks(callbacks)
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                assertDraws(scenario)
                assertEquals("Saved settings must not cause a startup recreation", 1, created.get())
                assertEquals(predictiveBack, application.isPredictiveBackEnabled())
                if (recreate) {
                    scenario.recreate()
                    assertDraws(scenario)
                    assertEquals(2, created.get())
                }
            }
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
            runBlocking {
                application.settingsStore.setPredictiveBack(originalSettings.predictiveBack)
                application.settingsStore.setBlurEnabled(originalSettings.blurEnabled)
            }
            instrumentation.runOnMainSync {
                application.setPredictiveBackEnabled(originalRuntimeFlag)
            }
        }
    }

    private fun assertDraws(scenario: ActivityScenario<MainActivity>) {
        lateinit var viewModel: HyperLpaViewModel
        scenario.onActivity { activity ->
            viewModel = ViewModelProvider(activity)[HyperLpaViewModel::class.java]
        }
        // A background-only draw must not count as a successful startup.
        runBlocking {
            withTimeout(10_000) { viewModel.state.first { it.initialDataLoaded } }
        }
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
