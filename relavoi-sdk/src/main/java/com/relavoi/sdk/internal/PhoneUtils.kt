package com.relavoi.sdk.internal

import com.relavoi.sdk.RelavoiException

/**
 * Phone-number utilities used at the SDK boundary. All public methods that accept a
 * phone number MUST validate via [requireValidE164] before doing anything else.
 *
 * Logging utilities also live here: never log a plaintext phone — use [maskPhone].
 */
internal object PhoneUtils {

    // Strict E.164: leading +, first digit 1-9, total 7-15 digits.
    private val E164_REGEX = Regex("^\\+[1-9]\\d{6,14}$")

    /** Returns true if [phone] is a syntactically valid E.164 number. */
    fun validateE164(phone: String): Boolean = E164_REGEX.matches(phone)

    /**
     * Throws [RelavoiException.Validation] if [phone] is not valid E.164. The error
     * message includes [fieldName] (so callers know which arg was bad) and the LAST
     * 4 DIGITS of the phone — never the full number, for privacy.
     */
    fun requireValidE164(phone: String, fieldName: String) {
        if (!validateE164(phone)) {
            val tail = phone.takeLast(4).ifBlank { "????" }
            throw RelavoiException.Validation(
                "$fieldName is not a valid E.164 phone number (ends in $tail)"
            )
        }
    }

    /**
     * Returns a privacy-preserving mask of [phone], e.g. `+****5678`. Safe to log.
     * For numbers shorter than 4 digits we return all asterisks.
     */
    fun maskPhone(phone: String): String {
        if (phone.length <= 4) return "+****"
        val tail = phone.takeLast(4)
        return "+****$tail"
    }
}
