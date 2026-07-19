package app.hyperlpa

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.core.content.edit
import app.hyperlpa.data.LpaRepository
import app.hyperlpa.data.cloud.NekokoCloudService
import app.hyperlpa.data.metadata.ProfileMetadataStore
import app.hyperlpa.data.settings.AppSettingsStore
import org.lsposed.hiddenapibypass.HiddenApiBypass

class HyperLpaApplication : Application() {
    val settingsStore by lazy { AppSettingsStore(this) }
    val metadataStore by lazy { ProfileMetadataStore(this) }
    val cloudService by lazy { NekokoCloudService(this) }
    val lpaRepository by lazy { LpaRepository(this, metadataStore = metadataStore) }

    override fun onCreate() {
        super.onCreate()
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback",
                "Landroid/telephony/",
                "Lcom/android/internal/telephony/",
            )
        }
        applyPredictiveBackFlag(
            getSharedPreferences(RuntimeUiPreferences, MODE_PRIVATE)
                .getBoolean(PredictiveBackKey, false),
        )
    }

    fun setPredictiveBackEnabled(enabled: Boolean) {
        getSharedPreferences(RuntimeUiPreferences, MODE_PRIVATE)
            .edit { putBoolean(PredictiveBackKey, enabled) }
        applyPredictiveBackFlag(enabled)
    }

    fun isPredictiveBackEnabled(): Boolean =
        getSharedPreferences(RuntimeUiPreferences, MODE_PRIVATE)
            .getBoolean(PredictiveBackKey, false)

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
    }
}
