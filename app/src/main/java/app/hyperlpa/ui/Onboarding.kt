package app.hyperlpa.ui

import app.hyperlpa.data.LpaRepositoryState
import app.hyperlpa.domain.model.ReaderInfo
import app.hyperlpa.domain.model.ReaderKind

enum class OnboardingReader {
    PHONE, USB, BLUETOOTH, PAIRED_DEVICE;

    fun accepts(reader: ReaderInfo): Boolean = when (this) {
        PHONE -> reader.deviceId == null && reader.kind in setOf(ReaderKind.NBRIDGE, ReaderKind.OMAPI, ReaderKind.TELEPHONY)
        USB -> reader.deviceId == null && reader.kind == ReaderKind.USB_CCID
        BLUETOOTH -> reader.deviceId == null && reader.kind == ReaderKind.BLE
        PAIRED_DEVICE -> reader.deviceId != null
    }
}

internal fun onboardingConnectedReader(lpa: LpaRepositoryState, reader: OnboardingReader): ReaderInfo? =
    lpa.selectedReader?.takeIf {
        reader.accepts(it) && it.available && (it.eid != null || lpa.euiccInfo != null)
    }

enum class OnboardingPreference {
    OPERATOR_ICONS, SIZE_ESTIMATES, PROFILE_ICONS, PROFILE_SIZES, ICCID,
    NAMES, PROVIDERS, COUNTRY_FLAGS, TAGS, REMINDERS, SWITCHES, SEARCH, READER_SELECTOR, EID,
}
