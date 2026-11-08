package app.hyperlpa.ui

import app.hyperlpa.data.LpaRepositoryState
import app.hyperlpa.domain.model.EuiccInfo
import app.hyperlpa.domain.model.ReaderInfo
import app.hyperlpa.domain.model.ReaderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingReaderTest {
    @Test fun pairedReadersDoNotMakeThisPhoneLookCompatible() {
        val paired = ReaderInfo("paired", "Other phone", ReaderKind.OMAPI, eid = "123", deviceId = "other")
        val lpa = LpaRepositoryState(readers = listOf(paired), selectedReaderId = paired.id, euiccInfo = EuiccInfo("123"))
        assertNull(onboardingConnectedReader(lpa, OnboardingReader.PHONE))
        assertEquals(paired, onboardingConnectedReader(lpa, OnboardingReader.PAIRED_DEVICE))
    }

    @Test fun anAdvertisedReaderWithoutACardIsNotReady() {
        val usb = ReaderInfo("usb", "USB reader", ReaderKind.USB_CCID)
        val lpa = LpaRepositoryState(readers = listOf(usb), selectedReaderId = usb.id)
        assertNull(onboardingConnectedReader(lpa, OnboardingReader.USB))
        assertEquals(usb, onboardingConnectedReader(lpa.copy(euiccInfo = EuiccInfo("123")), OnboardingReader.USB))
        assertNull(onboardingConnectedReader(lpa.copy(readers = listOf(usb.copy(available = false)), euiccInfo = EuiccInfo("123")), OnboardingReader.USB))
    }

    @Test fun unrelatedAndUnselectedCardsDoNotSatisfyTheChosenConnection() {
        val phone = ReaderInfo("phone", "Phone", ReaderKind.OMAPI, eid = "123")
        val usb = ReaderInfo("usb", "USB", ReaderKind.USB_CCID, eid = "456")
        val lpa = LpaRepositoryState(readers = listOf(phone, usb), selectedReaderId = usb.id, euiccInfo = EuiccInfo("456"))
        assertNull(onboardingConnectedReader(lpa, OnboardingReader.PHONE))
        assertEquals(usb, onboardingConnectedReader(lpa, OnboardingReader.USB))
        assertTrue(OnboardingReader.PHONE.accepts(phone))
        assertFalse(OnboardingReader.BLUETOOTH.accepts(usb))
    }
}
