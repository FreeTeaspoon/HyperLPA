package app.hyperlpa

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.core.content.edit
import app.hyperlpa.data.LpaRepository
import app.hyperlpa.data.backup.HyperLpaBackupManager
import app.hyperlpa.data.cloud.NekokoCloudService
import app.hyperlpa.data.history.NotificationHistoryStore
import app.hyperlpa.data.metadata.ProfileMetadataStore
import app.hyperlpa.data.settings.AppSettingsStore
import app.hyperlpa.data.support.SupportReportBuilder
import app.hyperlpa.provisioning.ProvisioningCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.lsposed.hiddenapibypass.HiddenApiBypass

class HyperLpaApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val settingsStore by lazy { AppSettingsStore(this) }
    val metadataStore by lazy { ProfileMetadataStore(this) }
    val cloudService by lazy { NekokoCloudService(this) }
    val notificationHistoryStore by lazy { NotificationHistoryStore(this) }
    val supportReportBuilder by lazy { SupportReportBuilder(this) }
    val backupManager by lazy { HyperLpaBackupManager(this, settingsStore, metadataStore) }
    val lpaRepository by lazy {
        LpaRepository(
            this,
            metadataStore = metadataStore,
            notificationHistoryStore = notificationHistoryStore,
        )
    }
    val provisioningCoordinator by lazy { ProvisioningCoordinator(this, lpaRepository) }

    override fun onCreate() {
        super.onCreate()
        // A restore spans two DataStores. If the process died between their commits, recover the
        // previous complete generation before any repository, worker, or UI can observe it.
        runBlocking(Dispatchers.IO) {
            if (HyperLpaBackupManager.hasInterruptedRestore(this@HyperLpaApplication)) {
                try {
                    backupManager.recoverInterruptedRestore()
                } catch (error: Exception) {
                    // Keep a valid journal for an idempotent DataStore rollback retry on the next
                    // launch, but do not trap the process in a startup crash loop if storage stays
                    // unavailable. Malformed journals are quarantined by the backup manager.
                    Log.e(RestoreLogTag, "Interrupted restore recovery will be retried", error)
                }
            }
            if (!HyperLpaBackupManager.hasInterruptedRestore(this@HyperLpaApplication)) {
                // A process can stop after an icon is copied or promoted but before its metadata
                // transaction commits. Do not run this while a valid restore journal still needs
                // the prior generation's icon files for a later rollback attempt.
                runCatching { metadataStore.cleanupOrphanedIconFiles() }
            }
        }
        runCatching {
            val exemptions = buildList {
                add("Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback")
                if (BuildConfig.HAS_PRIVILEGED_TELEPHONY) {
                    add("Landroid/telephony/")
                    add("Lcom/android/internal/telephony/")
                }
            }
            HiddenApiBypass.addHiddenApiExemptions(*exemptions.toTypedArray())
        }
        applyPredictiveBackFlag(
            getSharedPreferences(RuntimeUiPreferences, MODE_PRIVATE)
                .getBoolean(PredictiveBackKey, true),
        )
        applicationScope.launch {
            // A temporarily unavailable Keystore must not prevent the app from
            // starting. The migration is idempotent and will be retried next launch.
            runCatching { settingsStore.migrateLegacyRemoteReaderCredentials() }
        }
        applicationScope.launch {
            // Metadata is the durable source of truth. Rebuild missing/replaced WorkManager
            // requests after process death, app updates, force-stop, or a partial prior repair.
            // syncReminders also includes profiles that are not connected or visible in search.
            runCatching {
                metadataStore.syncReminders(
                    reminders = emptyMap(),
                    enabled = settingsStore.settings.first().scheduledReminders,
                )
            }
        }
        applicationScope.launch {
            runCatching { notificationHistoryStore.initialize() }
        }
        // Recover status for display only. Potentially completed downloads are never replayed.
        provisioningCoordinator
    }

    fun setPredictiveBackEnabled(enabled: Boolean) {
        getSharedPreferences(RuntimeUiPreferences, MODE_PRIVATE)
            .edit { putBoolean(PredictiveBackKey, enabled) }
        applyPredictiveBackFlag(enabled)
    }

    fun isPredictiveBackEnabled(): Boolean =
        getSharedPreferences(RuntimeUiPreferences, MODE_PRIVATE)
            .getBoolean(PredictiveBackKey, true)

    private fun applyPredictiveBackFlag(enabled: Boolean) {
        runCatching {
            val method = ApplicationInfo::class.java.getDeclaredMethod(
                "setEnableOnBackInvokedCallback",
                Boolean::class.javaPrimitiveType,
            )
            method.isAccessible = true
            method.invoke(applicationInfo, enabled)
        }
    }

    private companion object {
        const val RuntimeUiPreferences = "runtime_ui_preferences"
        const val PredictiveBackKey = "predictive_back"
        const val RestoreLogTag = "HyperLpaRestore"
    }
}
