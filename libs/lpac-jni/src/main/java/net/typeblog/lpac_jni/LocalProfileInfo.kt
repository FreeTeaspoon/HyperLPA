package net.typeblog.lpac_jni

data class LocalProfileInfo(
    val iccid: String,
    val state: State,
    val name: String,
    val nickName: String,
    val providerName: String,
    val isdpAID: String,
    val profileClass: ProfileClass,
    val iconBase64: String?,
    val notificationAddress: String?,
    val mccMnc: String?,
    val gid1: String?,
    val gid2: String?,
    val notificationOperations: Set<String> = emptySet(),
    val dpOid: String? = null,
    val profilePolicyRules: Set<String> = emptySet(),
) {
    enum class State {
        Enabled,
        Disabled;

        companion object {
            @JvmStatic
            fun fromString(str: String?) =
                when (str?.lowercase()) {
                    "enabled" -> Enabled
                    "disabled" -> Disabled
                    else -> Disabled
                }
        }
    }

}
