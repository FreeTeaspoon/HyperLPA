package app.hyperlpa.remote

private const val OtpKeywords =
    """otp|one[\s-]?time(?:\s+password)?|verification|security|login|auth(?:entication)?|passcode|code|pin"""
private val otpNearKeyword = Regex(
    """(?i)\b(?:$OtpKeywords)\b[^0-9]{0,24}(?<![0-9])([0-9]{4,8})(?![0-9])|(?<![0-9])([0-9]{4,8})(?![0-9])[^0-9]{0,24}\b(?:$OtpKeywords)\b""",
)

/** A complete 4–8 digit code within 24 non-digit characters of a code keyword. */
internal fun detectOtp(text: String): IntRange? = otpNearKeyword.find(text)?.let {
    (it.groups[1] ?: it.groups[2])?.range
}
