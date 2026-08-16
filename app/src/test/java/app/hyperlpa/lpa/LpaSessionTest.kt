package app.hyperlpa.lpa

import app.hyperlpa.domain.model.ReaderInfo
import app.hyperlpa.domain.model.ReaderKind
import net.typeblog.lpac_jni.ApduInterface
import net.typeblog.lpac_jni.EuiccInfo2
import net.typeblog.lpac_jni.LocalProfileAssistant
import net.typeblog.lpac_jni.Version
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.lang.reflect.Proxy

class LpaSessionTest {
    @Test
    fun initializationEuiccInfoIsReusedUntilAnExplicitRefresh() {
        val initializationInfo = euiccInfo("initial")
        val laterInfo = euiccInfo("later")
        var liveReads = 0
        val assistant = Proxy.newProxyInstance(
            LocalProfileAssistant::class.java.classLoader,
            arrayOf(LocalProfileAssistant::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getEuiccInfo2" -> laterInfo.also { liveReads += 1 }
                "close" -> Unit
                else -> error("Unexpected LocalProfileAssistant call: ${method.name}")
            }
        } as LocalProfileAssistant
        val apdu = Proxy.newProxyInstance(
            ApduInterface::class.java.classLoader,
            arrayOf(ApduInterface::class.java),
        ) { _, method, _ -> error("Unexpected APDU call: ${method.name}") } as ApduInterface
        val session = LpaSession(
            reader = ReaderInfo("reader", "Reader", ReaderKind.OMAPI),
            aid = "A0000005591010FFFFFFFF8900000100",
            assistant = assistant,
            requiresProfileSwitchRefresh = true,
            initialEuiccInfo2 = initializationInfo,
            apduInterface = apdu,
        )

        assertSame(initializationInfo, session.readEuiccInfo2())
        assertEquals(0, liveReads)
        assertSame(initializationInfo, session.readEuiccInfo2())
        assertEquals(0, liveReads)
        assertSame(laterInfo, session.readEuiccInfo2(refresh = true))
        assertEquals(1, liveReads)
    }

    @Test
    fun missingInitializationEuiccInfoDoesNotCauseAnImmediateDuplicateRead() {
        val laterInfo = euiccInfo("later")
        var liveReads = 0
        val assistant = Proxy.newProxyInstance(
            LocalProfileAssistant::class.java.classLoader,
            arrayOf(LocalProfileAssistant::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getEuiccInfo2" -> laterInfo.also { liveReads += 1 }
                "close" -> Unit
                else -> error("Unexpected LocalProfileAssistant call: ${method.name}")
            }
        } as LocalProfileAssistant
        val apdu = Proxy.newProxyInstance(
            ApduInterface::class.java.classLoader,
            arrayOf(ApduInterface::class.java),
        ) { _, method, _ -> error("Unexpected APDU call: ${method.name}") } as ApduInterface
        val session = LpaSession(
            reader = ReaderInfo("reader", "Reader", ReaderKind.OMAPI),
            aid = "A0000005591010FFFFFFFF8900000100",
            assistant = assistant,
            requiresProfileSwitchRefresh = true,
            initialEuiccInfo2 = null,
            apduInterface = apdu,
        )

        assertNull(session.readEuiccInfo2())
        assertEquals(0, liveReads)
        assertNull(session.readEuiccInfo2())
        assertEquals(0, liveReads)
        assertSame(laterInfo, session.readEuiccInfo2(refresh = true))
        assertEquals(1, liveReads)
    }

    private fun euiccInfo(label: String) = EuiccInfo2(
        sgp22Version = Version(2, 2, 0),
        profileVersion = Version(2, 3, 0),
        euiccFirmwareVersion = Version(1, 0, 0),
        globalPlatformVersion = Version(2, 3, 0),
        sasAccreditationNumber = label,
        ppVersion = Version(1, 0, 0),
        freeNvram = 1,
        freeRam = 1,
        euiccCiPKIdListForSigning = emptySet(),
        euiccCiPKIdListForVerification = emptySet(),
    )
}
