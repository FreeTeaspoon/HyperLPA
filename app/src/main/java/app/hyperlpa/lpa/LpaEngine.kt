package app.hyperlpa.lpa

import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.domain.model.ReaderInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import net.typeblog.lpac_jni.ApduInterface
import net.typeblog.lpac_jni.LocalProfileAssistant
import net.typeblog.lpac_jni.impl.HttpInterfaceImpl
import net.typeblog.lpac_jni.impl.LocalProfileAssistantImpl

internal data class ReaderEndpoint(
    val info: ReaderInfo,
    val requiresProfileSwitchRefresh: Boolean = true,
    val openApduInterface: suspend () -> ApduInterface,
)

internal interface ReaderProvider {
    suspend fun listReaders(): List<ReaderEndpoint>
    fun close() = Unit
}

internal class LpaSession(
    val reader: ReaderInfo,
    val aid: String,
    val assistant: LocalProfileAssistant,
    val requiresProfileSwitchRefresh: Boolean,
    private val apduInterface: ApduInterface,
) : AutoCloseable {
    override fun close() {
        val assistantClosed = runCatching { assistant.close() }.isSuccess
        if (!assistantClosed) runCatching { apduInterface.disconnect() }
    }
}

internal object LpaSessionFactory {
    suspend fun open(
        endpoint: ReaderEndpoint,
        settings: AppSettings,
        verboseLoggingFlow: Flow<Boolean> = flowOf(settings.developerMode && settings.apduLogging),
    ): LpaSession {
        var lastFailure: Throwable? = null
        for (rawAid in settings.isdrAids) {
            var apdu: ApduInterface? = null
            var assistant: LocalProfileAssistant? = null
            try {
                val aid = rawAid.trim().uppercase()
                val aidBytes = decodeIsdrAid(aid)
                apdu = endpoint.openApduInterface()
                assistant = LocalProfileAssistantImpl(
                    aidBytes,
                    apdu,
                    HttpInterfaceImpl(
                        verboseLoggingFlow = verboseLoggingFlow,
                        httpProxyFlow = flowOf(""),
                    ),
                )
                assistant.setEs10xMss(settings.es10xMss.toByte())
                return LpaSession(
                    reader = endpoint.info,
                    aid = aid,
                    assistant = assistant,
                    requiresProfileSwitchRefresh = endpoint.requiresProfileSwitchRefresh,
                    apduInterface = apdu,
                )
            } catch (error: Throwable) {
                lastFailure = error
                val assistantClosed = assistant?.let { opened ->
                    runCatching { opened.close() }.isSuccess
                } ?: false
                if (!assistantClosed) runCatching { apdu?.disconnect() }
                // Cancellation can arrive after the transport or native context has
                // opened. Release those resources before preserving coroutine
                // cancellation semantics.
                if (error is CancellationException) throw error
            }
        }
        throw IllegalStateException(
            "None of the configured ISD-R AIDs could be opened on ${endpoint.info.name}",
            lastFailure,
        )
    }
}

internal fun decodeIsdrAid(value: String): ByteArray {
    val normalized = value.trim()
    require(normalized.length in 10..32 && normalized.length % 2 == 0) {
        "An ISD-R AID must contain between 5 and 16 bytes"
    }
    require(normalized.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
        "An ISD-R AID must contain only hexadecimal characters"
    }
    return normalized.hexToByteArray()
}
