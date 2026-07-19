package app.hyperlpa.lpa

import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.domain.model.ReaderInfo
import kotlinx.coroutines.flow.flowOf
import net.typeblog.lpac_jni.ApduInterface
import net.typeblog.lpac_jni.LocalProfileAssistant
import net.typeblog.lpac_jni.impl.HttpInterfaceImpl
import net.typeblog.lpac_jni.impl.LocalProfileAssistantImpl

internal data class ReaderEndpoint(
    val info: ReaderInfo,
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
    private val apduInterface: ApduInterface,
) : AutoCloseable {
    override fun close() {
        runCatching { assistant.close() }
        runCatching { apduInterface.disconnect() }
    }
}

internal object LpaSessionFactory {
    suspend fun open(
        endpoint: ReaderEndpoint,
        settings: AppSettings,
    ): LpaSession {
        var lastFailure: Throwable? = null
        for (aid in settings.isdrAids) {
            val apdu = endpoint.openApduInterface()
            try {
                val assistant = LocalProfileAssistantImpl(
                    aid.hexToByteArray(),
                    apdu,
                    HttpInterfaceImpl(
                        verboseLoggingFlow = flowOf(settings.apduLogging),
                        ignoreTLSCertificateFlow = flowOf(false),
                        httpProxyFlow = flowOf(""),
                    ),
                )
                assistant.setEs10xMss(settings.es10xMss.toByte())
                return LpaSession(endpoint.info, aid, assistant, apdu)
            } catch (error: Throwable) {
                lastFailure = error
                runCatching { apdu.disconnect() }
            }
        }
        throw IllegalStateException(
            "None of the configured ISD-R AIDs could be opened on ${endpoint.info.name}",
            lastFailure,
        )
    }
}
