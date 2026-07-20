package app.hyperlpa.domain.model

data class IccidDetails(
    val checksumValid: Boolean?,
    val issuerPrefix: String?,
)

fun analyzeIccid(value: String): IccidDetails {
    val digits = value.trim()
    val numeric = digits.isNotEmpty() && digits.all(Char::isDigit)
    return IccidDetails(
        checksumValid = digits
            .takeIf { numeric && it.length >= 2 }
            ?.let(::hasValidLuhnChecksum),
        // The complete issuer identifier is variable-length. Seven digits is a useful,
        // non-authoritative prefix for diagnostics and catalog lookups.
        issuerPrefix = digits
            .takeIf { numeric && it.startsWith("89") && it.length >= 7 }
            ?.take(7),
    )
}

private fun hasValidLuhnChecksum(value: String): Boolean {
    val sum = value.reversed().mapIndexed { index, character ->
        character.digitToInt().let { digit ->
            if (index % 2 == 1) (digit * 2).let { if (it > 9) it - 9 else it } else digit
        }
    }.sum()
    return sum % 10 == 0
}
