package app.hyperlpa.remote

private const val OtpKeywords =
    """otp|one[\s-]?time(?:\s+password)?|verification|security|login|auth(?:entication)?|passcode|code|pin"""
private val otpKeyword = Regex("""(?i)\b(?:$OtpKeywords)\b""")
private val otpAfterKeyword = Regex("""(?i)\b(?:$OtpKeywords)\b[^0-9]{0,24}([0-9]{4,8})(?![0-9])""")
private val standaloneOtp = Regex("""(?<![0-9])([0-9]{4,8})(?![0-9])""")

/** The range of a likely one-time code in [text], or null when the text has no code keyword. */
internal fun detectOtp(text: String): IntRange? {
    otpAfterKeyword.find(text)?.groups?.get(1)?.let { return it.range }
    if (!otpKeyword.containsMatchIn(text)) return null
    return standaloneOtp.find(text)?.groups?.get(1)?.range
}
