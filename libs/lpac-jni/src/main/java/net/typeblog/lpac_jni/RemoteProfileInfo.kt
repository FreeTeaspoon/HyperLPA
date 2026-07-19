package net.typeblog.lpac_jni

// TODO: We need to export profilePolicyRules here as well (currently unsupported by lpac)
data class RemoteProfileInfo(
    val iccid: String,
    val name: String,
    val providerName: String,
    val profileClass: ProfileClass,
    val iconBase64: String?,
    val mccMnc: String?,
    val gid1: String?,
    val gid2: String?,
)
