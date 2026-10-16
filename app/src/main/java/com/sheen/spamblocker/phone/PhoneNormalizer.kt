package com.sheen.spamblocker.phone

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat

/** Result of normalizing a caller handle (SPEC §4). */
sealed interface NormalizedNumber {
    /** No usable number. Presentation-based hiding is decided by the caller. */
    data object Hidden : NormalizedNumber

    data class Parsed(
        val e164: String,
        val countryCode: Int,
        /** National significant number, e.g. `9876543210`. */
        val nationalNumber: String,
    ) : NormalizedNumber {
        val isIndian: Boolean get() = countryCode == INDIA_COUNTRY_CODE
    }

    /** libphonenumber couldn't parse it; [e164] is `+` followed by the raw digits. */
    data class Unparsed(val e164: String) : NormalizedNumber
}

const val INDIA_COUNTRY_CODE = 91

class PhoneNormalizer(
    private val defaultRegion: String = "IN",
    private val util: PhoneNumberUtil = PhoneNumberUtil.getInstance(),
) {
    fun normalize(raw: String?): NormalizedNumber {
        if (raw.isNullOrBlank()) return NormalizedNumber.Hidden
        val digits = raw.filter(Char::isDigit)
        if (digits.isEmpty()) return NormalizedNumber.Hidden

        return try {
            val number = util.parse(raw, defaultRegion)
            NormalizedNumber.Parsed(
                e164 = util.format(number, PhoneNumberFormat.E164),
                countryCode = number.countryCode,
                nationalNumber = util.getNationalSignificantNumber(number),
            )
        } catch (_: NumberParseException) {
            NormalizedNumber.Unparsed("+$digits")
        }
    }
}
