package com.relavoi.sdk.internal

import com.relavoi.sdk.RelavoiException

/**
 * Phone-number handling at the SDK boundary.
 *
 * This used to enforce strict E.164 with a `^\+[1-9]\d{6,14}$` regex, and that
 * was wrong in both directions. It accepted numbers the API refuses, such as a
 * +44 mobile or a Nigerian landline, so the SDK promised a session it could not
 * get. More awkwardly it REFUSED the forms the API accepts: a Nigerian typing
 * `09000000001`, or pasting `0900 000 0001` out of a contact list, got a
 * client-side error for a number the server would have taken.
 *
 * So the client-side check is now deliberately light. The server owns the rule
 * -- it reads it from the countries table and can change it without a new SDK
 * release -- and this only catches the mistakes that are obviously not a phone
 * number at all, such as an empty field or a name typed in the wrong box.
 * Anything number-shaped goes to the server, and the server's refusal comes
 * back as [RelavoiException.NumberNotSupported].
 *
 * Separators are stripped using the same set the server strips, so a value the
 * API would accept is never rejected here. Keeping those two lists in step is
 * the whole point; if the server's set changes, change this one.
 *
 * No function here ever puts any part of a number into a message. The caller
 * passed the value in, so repeating it buys nothing, and these strings reach
 * logcat and crash reporters.
 */
internal object PhoneUtils {

    /**
     * Spaces, brackets, hyphens, dots, the non-breaking space and the Unicode
     * dash range. Mirrors `strip()` in the backend's phone service.
     *
     * The NBSP and the dash range are written as escapes on purpose: a literal
     * NBSP in source is invisible and survives a review unnoticed.
     */
    private val SEPARATORS = Regex("[\\s()\\-.\\u00A0\\u2010-\\u2015]")

    /** Optional leading plus, then at least one digit, and nothing else. */
    private val NUMBER_SHAPED = Regex("^\\+?[0-9]+$")

    /**
     * Remove the separators a person types or a contact list carries. The
     * result is what gets sent, so the value in a URL or a JSON body is always
     * plain digits with an optional plus.
     */
    fun canonicalise(phone: String): String = SEPARATORS.replace(phone.trim(), "")

    /** True if [phone] could be a phone number. Says nothing about which country. */
    fun looksLikeNumber(phone: String): Boolean = NUMBER_SHAPED.matches(canonicalise(phone))

    /**
     * Returns the canonical form of [phone], or throws
     * [RelavoiException.Validation] if it is not number-shaped.
     *
     * The message names the field and describes the expected shape. It does not
     * contain the value, not even a few digits of it.
     */
    fun requireNumberShaped(phone: String, fieldName: String): String {
        val canonical = canonicalise(phone)
        if (!NUMBER_SHAPED.matches(canonical)) {
            throw RelavoiException.Validation(
                "$fieldName is not a phone number. Expected digits, optionally starting with '+'."
            )
        }
        return canonical
    }
}
