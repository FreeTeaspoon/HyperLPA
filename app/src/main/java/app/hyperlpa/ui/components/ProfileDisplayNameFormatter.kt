package app.hyperlpa.ui.components

import app.hyperlpa.data.settings.PhoneFormatStrategy
import app.hyperlpa.domain.model.ProfileInfo
import com.google.i18n.phonenumbers.PhoneNumberUtil

data class FormattedProfileDisplayName(
    val fullText: String,
    val nameText: String,
    val phoneNumbers: List<String>,
) {
    val phoneText: String = phoneNumbers.joinToString(" · ")
    val hasPhoneNumber: Boolean = phoneNumbers.isNotEmpty()
}

fun formatProfileDisplayName(
    profile: ProfileInfo,
    strategy: PhoneFormatStrategy,
    fallback: String = "eSIM profile",
): FormattedProfileDisplayName {
    val rawName = profile.nickname.ifBlank { profile.name.ifBlank { fallback } }
    return formatProfileDisplayName(
        rawName = rawName,
        strategy = strategy,
        mcc = profile.mcc,
        mnc = profile.mnc,
        iccid = profile.iccid,
    )
}

fun formatProfileDisplayName(
    rawName: String,
    strategy: PhoneFormatStrategy,
    mcc: String? = null,
    mnc: String? = null,
    iccid: String? = null,
): FormattedProfileDisplayName {
    if (rawName.isBlank() || strategy == PhoneFormatStrategy.OFF) {
        return FormattedProfileDisplayName(rawName, rawName, emptyList())
    }

    val cacheKey = ProfileDisplayNameCacheKey(rawName, strategy, mcc, mnc, iccid)
    synchronized(ProfileDisplayNameCache) {
        ProfileDisplayNameCache[cacheKey]?.let { return it }
    }

    val formatted = runCatching {
        val phoneNumberUtil = PhoneNumberUtil.getInstance()
        val region = profileRegion(mcc, mnc, iccid, phoneNumberUtil)
        val cleanedName = buildString(rawName.length) {
            rawName.forEach { character ->
                append(if (character.isDigit() || character in PhoneCharacters) character else ' ')
            }
        }

        val matches = phoneNumberUtil.findNumbers(cleanedName, region)
            .mapNotNull { match ->
                val rawPhoneNumber = match.rawString()
                val type = phoneNumberUtil.getNumberType(match.number())
                val isMobile = type == PhoneNumberUtil.PhoneNumberType.MOBILE ||
                    type == PhoneNumberUtil.PhoneNumberType.FIXED_LINE_OR_MOBILE
                val shouldFormat = when (strategy) {
                    PhoneFormatStrategy.INTERNATIONAL_ONLY -> '+' in rawPhoneNumber
                    PhoneFormatStrategy.INTERNATIONAL_AND_MOBILE -> '+' in rawPhoneNumber || isMobile
                    PhoneFormatStrategy.INTERNATIONAL_AND_ALL -> true
                    PhoneFormatStrategy.OFF -> false
                }
                if (!shouldFormat || match.start() !in 0..rawName.length || match.end() > rawName.length) {
                    null
                } else {
                    DetectedPhoneNumber(
                        start = match.start(),
                        end = match.end(),
                        formatted = phoneNumberUtil.format(
                            match.number(),
                            PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL,
                        ).normalizePhoneSpacing(),
                    )
                }
            }
            .sortedBy(DetectedPhoneNumber::start)

        if (matches.isEmpty()) {
            return@runCatching FormattedProfileDisplayName(rawName, rawName, emptyList())
        }

        var cursor = 0
        val fullText = buildString {
            matches.forEach { match ->
                append(rawName, cursor, match.start)
                append(match.formatted)
                cursor = match.end
            }
            append(rawName, cursor, rawName.length)
        }

        cursor = 0
        val nameText = buildString {
            matches.forEach { match ->
                append(rawName, cursor, match.start)
                cursor = match.end
            }
            append(rawName, cursor, rawName.length)
        }.replace(Whitespace, " ").trim()

        FormattedProfileDisplayName(
            fullText = fullText,
            nameText = nameText,
            phoneNumbers = matches.map(DetectedPhoneNumber::formatted),
        )
    }.getOrElse {
        FormattedProfileDisplayName(rawName, rawName, emptyList())
    }
    synchronized(ProfileDisplayNameCache) {
        ProfileDisplayNameCache[cacheKey] = formatted
    }
    return formatted
}

private data class ProfileDisplayNameCacheKey(
    val rawName: String,
    val strategy: PhoneFormatStrategy,
    val mcc: String?,
    val mnc: String?,
    val iccid: String?,
)

private const val ProfileDisplayNameCacheSize = 128
private val ProfileDisplayNameCache = object : LinkedHashMap<
    ProfileDisplayNameCacheKey,
    FormattedProfileDisplayName,
>(ProfileDisplayNameCacheSize, 0.75f, true) {
    override fun removeEldestEntry(
        eldest: MutableMap.MutableEntry<ProfileDisplayNameCacheKey, FormattedProfileDisplayName>,
    ): Boolean = size > ProfileDisplayNameCacheSize
}

private data class DetectedPhoneNumber(
    val start: Int,
    val end: Int,
    val formatted: String,
)

private val PhoneCharacters = setOf('+', ' ', '.', '-', '(', ')')
private val Whitespace = Regex("\\s+")

private fun String.normalizePhoneSpacing(): String = replace('\u00A0', ' ')

private fun profileRegion(
    mcc: String?,
    mnc: String?,
    iccid: String?,
    phoneNumberUtil: PhoneNumberUtil,
): String {
    mccRegion(mcc, mnc, iccid)?.let { return it }

    val digits = iccid.orEmpty().filter(Char::isDigit)
    if (digits.startsWith("89")) {
        val issuer = digits.drop(2)
        for (length in 1..minOf(3, issuer.length)) {
            val callingCode = issuer.take(length).toIntOrNull() ?: continue
            val region = phoneNumberUtil.getRegionCodeForCountryCode(callingCode)
            if (region != null && region != "ZZ") return region
        }
    }

    return "US"
}

private fun mccRegion(mcc: String?, mnc: String?, iccid: String?): String? {
    when (mcc) {
        "234" -> {
            if (mnc in setOf("03", "28", "50")) return "JE"
            if (mnc in setOf("18", "36", "58", "73")) return "IM"
            if (mnc in setOf("55", "36")) return "GG"
            return "GB"
        }
        "310" -> return if (mnc in setOf("110", "140", "370", "480")) "GU" else "US"
        "311" -> return if (mnc in setOf("310", "320", "470")) "VI" else "US"
        "312", "313" -> return if (mnc in setOf("510", "790")) "PR" else "US"
        "338" -> return digicelCaribbeanRegion(mnc, iccid) ?: "JM"
        "340" -> return if (mnc == "03") "MF" else "MQ"
        "425" -> return if (mnc in setOf("05", "06")) "PS" else "IL"
        "505" -> return if (mnc == "10") "NF" else "AU"
        "647" -> return if (mnc == "02") "YT" else "RE"
    }

    return when (mcc) {
        "202" -> "GR"
        "204" -> "NL"
        "206" -> "BE"
        "208" -> "FR"
        "212" -> "MC"
        "213" -> "AD"
        "214" -> "ES"
        "216" -> "HU"
        "218" -> "BA"
        "219" -> "HR"
        "220" -> "RS"
        "222" -> "IT"
        "225" -> "VA"
        "226" -> "RO"
        "228" -> "CH"
        "230" -> "CZ"
        "231" -> "SK"
        "232" -> "AT"
        "235" -> "GB"
        "238" -> "DK"
        "240" -> "SE"
        "242" -> "NO"
        "244" -> "FI"
        "246" -> "LT"
        "247" -> "LV"
        "248" -> "EE"
        "250" -> "RU"
        "255" -> "UA"
        "257" -> "BY"
        "259" -> "MD"
        "260" -> "PL"
        "262" -> "DE"
        "266" -> "GI"
        "268" -> "PT"
        "270" -> "LU"
        "272" -> "IE"
        "274" -> "IS"
        "276" -> "AL"
        "278" -> "MT"
        "280" -> "CY"
        "282", "298" -> "GE"
        "283" -> "AM"
        "284" -> "BG"
        "286" -> "TR"
        "288" -> "FO"
        "290" -> "GL"
        "292" -> "SM"
        "293" -> "SI"
        "294" -> "MK"
        "295" -> "LI"
        "297" -> "ME"
        "302" -> "CA"
        "308" -> "PM"
        "314", "315", "316" -> "US"
        "330" -> "PR"
        "332" -> "VI"
        "334" -> "MX"
        "342" -> "BB"
        "344" -> "AG"
        "346" -> "KY"
        "348" -> "VG"
        "350" -> "BM"
        "352" -> "GD"
        "354" -> "MS"
        "356" -> "KN"
        "358" -> "LC"
        "360" -> "VC"
        "362" -> "CW"
        "363" -> "AW"
        "364" -> "BS"
        "365" -> "AI"
        "366" -> "DM"
        "368" -> "CU"
        "370" -> "DO"
        "372" -> "HT"
        "374" -> "TT"
        "376" -> "TC"
        "400" -> "AZ"
        "401" -> "KZ"
        "402" -> "BT"
        "404", "405", "406" -> "IN"
        "410" -> "PK"
        "412" -> "AF"
        "413" -> "LK"
        "414" -> "MM"
        "415" -> "LB"
        "416" -> "JO"
        "417" -> "SY"
        "418" -> "IQ"
        "419" -> "KW"
        "420" -> "SA"
        "421" -> "YE"
        "422" -> "OM"
        "424", "430", "431" -> "AE"
        "426" -> "BH"
        "427" -> "QA"
        "428" -> "MN"
        "429" -> "NP"
        "432" -> "IR"
        "434" -> "UZ"
        "436" -> "TJ"
        "437" -> "KG"
        "438" -> "TM"
        "440", "441" -> "JP"
        "450" -> "KR"
        "452" -> "VN"
        "454" -> "HK"
        "455" -> "MO"
        "456" -> "KH"
        "457" -> "LA"
        "460", "461" -> "CN"
        "466" -> "TW"
        "467" -> "KP"
        "470" -> "BD"
        "472" -> "MV"
        "502" -> "MY"
        "510" -> "ID"
        "514" -> "TL"
        "515" -> "PH"
        "520" -> "TH"
        "525" -> "SG"
        "528" -> "BN"
        "530" -> "NZ"
        "536" -> "NR"
        "537" -> "PG"
        "539" -> "TO"
        "540" -> "SB"
        "541" -> "VU"
        "542" -> "FJ"
        "543" -> "WF"
        "544" -> "AS"
        "545" -> "KI"
        "546" -> "NC"
        "547" -> "PF"
        "548" -> "CK"
        "549" -> "WS"
        "550" -> "FM"
        "551" -> "MH"
        "552" -> "PW"
        "602" -> "EG"
        "603" -> "DZ"
        "604" -> "MA"
        "605" -> "TN"
        "606" -> "LY"
        "607" -> "GM"
        "608" -> "SN"
        "609" -> "MR"
        "610" -> "ML"
        "611" -> "GN"
        "612" -> "CI"
        "613" -> "BF"
        "614" -> "NE"
        "615" -> "TG"
        "616" -> "BJ"
        "617" -> "MU"
        "618" -> "LR"
        "619" -> "SL"
        "620" -> "GH"
        "621" -> "NG"
        "622" -> "TD"
        "623" -> "CF"
        "624" -> "CM"
        "625" -> "CV"
        "626" -> "ST"
        "627" -> "GQ"
        "628" -> "GA"
        "629" -> "CG"
        "630" -> "CD"
        "631" -> "AO"
        "632" -> "GW"
        "633" -> "SC"
        "634" -> "SD"
        "635" -> "RW"
        "636" -> "ET"
        "637" -> "SO"
        "638" -> "DJ"
        "639" -> "KE"
        "640" -> "TZ"
        "641" -> "UG"
        "642" -> "BI"
        "643" -> "MZ"
        "645" -> "ZM"
        "646" -> "MG"
        "648" -> "ZW"
        "649" -> "NA"
        "650" -> "MW"
        "651" -> "LS"
        "652" -> "BW"
        "653" -> "SZ"
        "654" -> "KM"
        "655" -> "ZA"
        "657" -> "ER"
        "658" -> "SH"
        "659" -> "SS"
        "702" -> "BZ"
        "704" -> "GT"
        "706" -> "SV"
        "708" -> "HN"
        "710" -> "NI"
        "712" -> "CR"
        "714" -> "PA"
        "716" -> "PE"
        "722" -> "AR"
        "724" -> "BR"
        "730" -> "CL"
        "732" -> "CO"
        "734" -> "VE"
        "736" -> "BO"
        "738" -> "GY"
        "740" -> "EC"
        "742" -> "GF"
        "744" -> "PY"
        "746" -> "SR"
        "748" -> "UY"
        "750" -> "FK"
        "995" -> "IO"
        else -> null
    }
}

private fun digicelCaribbeanRegion(mnc: String?, iccid: String?): String? {
    if (mnc !in setOf("05", "050") || iccid == null || !iccid.startsWith("890105") || iccid.length < 12) {
        return null
    }
    return when (iccid[9]) {
        '0' -> "JM"
        '1' -> "LC"
        '2' -> "VC"
        '3' -> "GD"
        '5' -> "BB"
        '6' -> "KY"
        '8' -> when (iccid.substring(9, 12)) {
            "830" -> "AI"
            "831" -> "KN"
            "832" -> "DM"
            "833" -> "BM"
            "834" -> "AG"
            "837" -> "TC"
            "843" -> "MS"
            else -> null
        }
        '9' -> "HT"
        else -> null
    }
}
