package net.typeblog.lpac_jni

data class ProfileDownloadInput(
    val address: String,
    val matchingId: String?,
    /** Optional OID from the activation code, matched against CERT.DPauth.ECDSA. */
    val smdpOid: String?,
    val imei: String?,
    val confirmationCode: String?
)
