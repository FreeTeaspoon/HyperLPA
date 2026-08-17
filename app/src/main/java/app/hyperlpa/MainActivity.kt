package app.hyperlpa

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
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
import androidx.core.content.edit
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.reminders.hasProfileReminderPermission
import app.hyperlpa.reminders.showTestProfileReminder
import app.hyperlpa.ui.HyperLpaApp
import app.hyperlpa.ui.BluetoothReaderUiState
import app.hyperlpa.ui.HyperLpaViewModel
import app.hyperlpa.ui.NavigationSnapshot
import app.hyperlpa.ui.theme.HyperLpaTheme
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanCustomCode
import io.github.g00fy2.quickie.config.BarcodeFormat
import io.github.g00fy2.quickie.config.ScannerConfig
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHostState

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
            notificationHistoryStore = applicationGraph.notificationHistoryStore,
            supportReportBuilder = applicationGraph.supportReportBuilder,
            provisioningCoordinator = applicationGraph.provisioningCoordinator,
        )
    }

    private lateinit var qrLauncher: ActivityResultLauncher<ScannerConfig>
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private var notificationPermissionGranted by mutableStateOf(true)
    private var bluetoothSupported by mutableStateOf(false)
    private var bluetoothPermissionGranted by mutableStateOf(false)
    private var bluetoothAdapterEnabled by mutableStateOf(false)
    private var refreshReadersAfterSettings by mutableStateOf(false)
    private var simStateReceiverRegistered = false
    private val snackbarHostState = SnackbarHostState()
    private val simStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isInitialStickyBroadcast) return
            viewModel.onSimStateChanged()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        savedInstanceState?.let { state ->
            viewModel.restoreNavigation(
                NavigationSnapshot(
                    selectedTab = state.getString(StateSelectedTab).orEmpty(),
                    route = state.getString(StateRoute),
                ),
            )
        }
        splashScreen.setKeepOnScreenCondition { !viewModel.state.value.settingsLoaded }
        enableEdgeToEdge()

        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            viewModel.completeRuntimePermissionRequest()
            refreshBluetoothState()
            viewModel.refreshReaders()
        }
        notificationPermissionGranted = hasProfileReminderPermission(this)
        notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            notificationPermissionGranted = it && hasProfileReminderPermission(this)
            viewModel.completeNotificationPermissionRequest(notificationPermissionGranted)
        }

        qrLauncher = registerForActivityResult(ScanCustomCode()) { result ->
            when (result) {
                is QRResult.QRSuccess -> {
                    val rawValue = result.content.rawValue
                    when {
                        rawValue == null -> showScannerMessage(R.string.activation_error_qr_read)
                        !viewModel.handleActivationCode(rawValue) -> {
                            showScannerMessage(R.string.activation_error_invalid)
                        }
                    }
                }
                is QRResult.QRError -> showScannerMessage(R.string.activation_error_qr_read)
                is QRResult.QRMissingPermission -> {
                    showScannerMessage(R.string.activation_error_camera_permission)
                    if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                        openAppPermissionSettings(refreshReadersOnReturn = false)
                    }
                }
                is QRResult.QRUserCanceled -> Unit
            }
        }
        refreshBluetoothState()

        setContent {
            val state = viewModel.state.collectAsStateWithLifecycle().value
            var firstReaderConfiguration by remember { mutableStateOf(true) }
            LaunchedEffect(
                state.lpa.initialized,
                state.settings.enableNBridge,
                state.settings.enableOmapi,
                BuildConfig.HAS_PRIVILEGED_TELEPHONY && state.settings.enableTelephony,
                state.settings.enableUsbCcid,
                state.settings.enableBle,
                state.settings.enableRemote,
            ) {
                val permissionRequestStarted = requestRuntimePermissions(
                    settings = state.settings,
                    userInitiated = false,
                )
                if (!state.lpa.initialized) return@LaunchedEffect
                if (firstReaderConfiguration) {
                    firstReaderConfiguration = false
                } else if (!permissionRequestStarted) {
                    viewModel.refreshReaders()
                }
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
                    backStack = viewModel.navigationBackStack,
                    viewModel = viewModel,
                    snackbarHostState = snackbarHostState,
                    notificationPermissionGranted = notificationPermissionGranted,
                    onRequestNotificationPermission = ::requestNotificationPermission,
                    onOpenNotificationSettings = ::openNotificationSettings,
                    onTestProfileReminder = { showTestProfileReminder(applicationContext) },
                    onScanQr = {
                        qrLauncher.launch(
                            ScannerConfig.build {
                                setBarcodeFormats(listOf(BarcodeFormat.FORMAT_QR_CODE))
                                setShowTorchToggle(true)
                                setShowCloseButton(true)
                                setKeepScreenOn(true)
                            },
                        )
                    },
                    bluetoothReaderState = BluetoothReaderUiState(
                        enabled = state.settings.enableBle,
                        supported = bluetoothSupported,
                        permissionGranted = bluetoothPermissionGranted,
                        adapterEnabled = bluetoothAdapterEnabled,
                    ),
                    onRefreshReaders = { discoverReaders(state.settings) },
                    onRequestBluetoothPermission = {
                        requestRuntimePermissions(state.settings, userInitiated = true)
                    },
                    onOpenBluetoothSettings = ::openBluetoothSettings,
                )
            }
        }

        intent?.let(::consumeActivationIntent)
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
                // Telephony broadcasts can originate from a privileged phone-process
                // UID rather than the system UID. These actions are protected by the
                // platform, so exported registration is required for reliable delivery.
                ContextCompat.RECEIVER_EXPORTED,
            )
            simStateReceiverRegistered = true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        viewModel.navigationSnapshot().let { snapshot ->
            outState.putString(StateSelectedTab, snapshot.selectedTab)
            outState.putString(StateRoute, snapshot.route)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        notificationPermissionGranted = hasProfileReminderPermission(this)
        val previousBluetoothState = Triple(
            bluetoothSupported,
            bluetoothPermissionGranted,
            bluetoothAdapterEnabled,
        )
        refreshBluetoothState()
        if (
            refreshReadersAfterSettings ||
            previousBluetoothState != Triple(
                bluetoothSupported,
                bluetoothPermissionGranted,
                bluetoothAdapterEnabled,
            )
        ) {
            refreshReadersAfterSettings = false
            viewModel.refreshReaders()
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
        consumeActivationIntent(intent)
    }

    private fun consumeActivationIntent(source: Intent) {
        val activationCode = source.dataString
        // Do not retain an activation/matching identifier in the Activity's task Intent. The
        // parsed draft remains in volatile ViewModel memory only.
        source.data = null
        source.clipData = null
        source.replaceExtras(null as Bundle?)
        setIntent(source)
        activationCode?.let(viewModel::handleActivationCode)
    }

    private fun requestRuntimePermissions(
        settings: AppSettings,
        userInitiated: Boolean,
    ): Boolean {
        val bluetoothPermissions = bluetoothRuntimePermissions()
        val missingBluetoothPermissions = if (settings.enableBle) {
            bluetoothPermissions.filterNot(::hasPermission)
        } else {
            emptyList()
        }
        val bluetoothPermissionPreviouslyRequested = getSharedPreferences(
            RuntimePermissionPreferences,
            Context.MODE_PRIVATE,
        ).getBoolean(BluetoothPermissionRequested, false)
        if (
            userInitiated &&
            missingBluetoothPermissions.isNotEmpty() &&
            bluetoothPermissionPreviouslyRequested &&
            missingBluetoothPermissions.all { permission ->
                !shouldShowRequestPermissionRationale(permission)
            }
        ) {
            openAppPermissionSettings(refreshReadersOnReturn = true)
            return true
        }
        val candidates = buildList {
            if (
                settings.enableBle &&
                (userInitiated || !bluetoothPermissionPreviouslyRequested)
            ) {
                addAll(bluetoothPermissions)
            }
            if (BuildConfig.HAS_PRIVILEGED_TELEPHONY && settings.enableTelephony) {
                add(Manifest.permission.READ_PHONE_STATE)
            }
        }
        val missing = candidates.filterNot(::hasPermission)
        if (missing.isEmpty()) return false
        if (!viewModel.beginRuntimePermissionRequest()) return true
        val requestsBluetoothPermission = missing.any(bluetoothPermissions::contains)
        return runCatching {
            permissionLauncher.launch(missing.toTypedArray())
            if (requestsBluetoothPermission) {
                getSharedPreferences(RuntimePermissionPreferences, Context.MODE_PRIVATE)
                    .edit { putBoolean(BluetoothPermissionRequested, true) }
            }
            true
        }.getOrElse {
            viewModel.completeRuntimePermissionRequest()
            false
        }
    }

    private fun discoverReaders(settings: AppSettings) {
        refreshBluetoothState()
        if (settings.enableBle && bluetoothSupported) {
            if (!bluetoothPermissionGranted) {
                requestRuntimePermissions(settings, userInitiated = true)
            } else if (!bluetoothAdapterEnabled) {
                openBluetoothSettings()
            }
        }
        // BLE remediation must never prevent the other enabled reader providers from refreshing.
        viewModel.refreshReaders()
    }

    private fun bluetoothRuntimePermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun refreshBluetoothState() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        bluetoothSupported = adapter != null &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        bluetoothPermissionGranted = bluetoothRuntimePermissions().all(::hasPermission)
        bluetoothAdapterEnabled = adapter != null &&
            bluetoothPermissionGranted &&
            runCatching { adapter.isEnabled }.getOrDefault(false)
    }

    private fun requestNotificationPermission(onResult: (Boolean) -> Unit) {
        val runtimePermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (runtimePermissionGranted) {
            notificationPermissionGranted = hasProfileReminderPermission(this)
            if (!notificationPermissionGranted) openNotificationSettings()
            onResult(notificationPermissionGranted)
            return
        }
        viewModel.retainNotificationPermissionContinuation(onResult)
        runCatching {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }.onFailure {
            viewModel.completeNotificationPermissionRequest(false)
        }
    }

    private fun showScannerMessage(messageRes: Int) {
        lifecycleScope.launch {
            snackbarHostState.showSnackbar(
                message = getString(messageRes),
                duration = SnackbarDuration.Long,
            )
        }
    }

    private fun openNotificationSettings() {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            },
        )
    }

    private fun openBluetoothSettings() {
        refreshReadersAfterSettings = true
        val opened = runCatching {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }.isSuccess || runCatching {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }.isSuccess
        if (!opened) {
            lifecycleScope.launch {
                snackbarHostState.showSnackbar(
                    message = getString(R.string.reader_bluetooth_settings_unavailable),
                    duration = SnackbarDuration.Long,
                )
            }
        }
    }

    private fun openAppPermissionSettings(refreshReadersOnReturn: Boolean) {
        refreshReadersAfterSettings = refreshReadersOnReturn
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                ),
            )
        }.onFailure {
            lifecycleScope.launch {
                snackbarHostState.showSnackbar(
                    message = getString(R.string.reader_bluetooth_settings_unavailable),
                    duration = SnackbarDuration.Long,
                )
            }
        }
    }

    private companion object {
        const val StateSelectedTab = "hyperlpa.selected-tab"
        const val StateRoute = "hyperlpa.route"
        const val RuntimePermissionPreferences = "hyperlpa-runtime-permissions"
        const val BluetoothPermissionRequested = "bluetooth-requested"
    }
}
