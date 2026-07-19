package app.hyperlpa

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.ui.HyperLpaApp
import app.hyperlpa.ui.HyperLpaViewModel
import app.hyperlpa.ui.theme.HyperLpaTheme
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanCustomCode
import io.github.g00fy2.quickie.config.BarcodeFormat
import io.github.g00fy2.quickie.config.ScannerConfig

class MainActivity : ComponentActivity() {
    private val applicationGraph: HyperLpaApplication
        get() = application as HyperLpaApplication

    private val viewModel by viewModels<HyperLpaViewModel> {
        HyperLpaViewModel.Factory(
            application = applicationGraph,
            settingsStore = applicationGraph.settingsStore,
            metadataStore = applicationGraph.metadataStore,
            repository = applicationGraph.lpaRepository,
            cloudService = applicationGraph.cloudService,
        )
    }

    private lateinit var qrLauncher: ActivityResultLauncher<ScannerConfig>
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private var qrCallback: ((String?) -> Unit)? = null
    private val requestedPermissions = mutableSetOf<String>()
    private var simStateReceiverRegistered = false
    private val simStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isInitialStickyBroadcast) return
            viewModel.onSimStateChanged()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            viewModel.refreshReaders()
        }

        qrLauncher = registerForActivityResult(ScanCustomCode()) { result ->
            val value = when (result) {
                is QRResult.QRSuccess -> result.content.rawValue
                is QRResult.QRError,
                is QRResult.QRMissingPermission,
                is QRResult.QRUserCanceled,
                -> null
            }
            qrCallback?.invoke(value)
            qrCallback = null
        }

        setContent {
            val state = viewModel.state.collectAsStateWithLifecycle().value
            var firstReaderConfiguration by remember { mutableStateOf(true) }
            LaunchedEffect(
                state.lpa.initialized,
                state.settings.enableNBridge,
                state.settings.enableOmapi,
                state.settings.enableTelephony,
                state.settings.enableUsbCcid,
                state.settings.enableBle,
                state.settings.enableRemote,
                state.settings.remoteReaderUrls,
            ) {
                val permissionRequestStarted = requestRuntimePermissions(state.settings)
                if (!state.lpa.initialized) return@LaunchedEffect
                if (firstReaderConfiguration) {
                    firstReaderConfiguration = false
                } else if (!permissionRequestStarted) {
                    viewModel.refreshReaders()
                }
            }
            LaunchedEffect(state.settings.scheduledReminders) {
                requestRuntimePermissions(state.settings)
            }
            LaunchedEffect(state.settings.predictiveBack) {
                if (applicationGraph.isPredictiveBackEnabled() != state.settings.predictiveBack) {
                    applicationGraph.setPredictiveBackEnabled(state.settings.predictiveBack)
                    recreate()
                }
            }
            HyperLpaTheme(settings = state.settings) {
                HyperLpaApp(
                    state = state,
                    viewModel = viewModel,
                    onScanQr = { callback ->
                        qrCallback = callback
                        qrLauncher.launch(
                            ScannerConfig.build {
                                setBarcodeFormats(listOf(BarcodeFormat.FORMAT_QR_CODE))
                                setShowTorchToggle(true)
                                setShowCloseButton(true)
                                setKeepScreenOn(true)
                            },
                        )
                    },
                )
            }
        }

        intent?.dataString?.let(viewModel::handleActivationCode)
    }

    override fun onStart() {
        super.onStart()
        if (!simStateReceiverRegistered) {
            val filter = IntentFilter("android.intent.action.SIM_STATE_CHANGED").apply {
                addAction("android.telephony.action.SIM_CARD_STATE_CHANGED")
                addAction("android.telephony.action.SIM_APPLICATION_STATE_CHANGED")
            }
            ContextCompat.registerReceiver(
                this,
                simStateReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
            simStateReceiverRegistered = true
        }
    }

    override fun onStop() {
        if (simStateReceiverRegistered) {
            unregisterReceiver(simStateReceiver)
            simStateReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.dataString?.let(viewModel::handleActivationCode)
    }

    private fun requestRuntimePermissions(settings: AppSettings): Boolean {
        val candidates = buildList {
            if (settings.enableBle) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }
            if (settings.enableTelephony) add(Manifest.permission.READ_PHONE_STATE)
            if (settings.scheduledReminders && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = candidates.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED &&
                requestedPermissions.add(permission)
        }
        if (missing.isEmpty()) return false
        permissionLauncher.launch(missing.toTypedArray())
        return true
    }
}
