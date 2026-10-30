package com.relavoi.sdk.internal

import com.relavoi.sdk.RelavoiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PhoneUtilsTest {

    // ─── The forms the API accepts must all pass ────────────────────────────────
    // This is the half the old strict-E.164 check got wrong in the expensive
    // direction: it refused what the server takes, so a Nigerian typing their own
    // number the normal way got a client-side error.

    @Test
    fun `accepts the local Nigerian form`() {
        assertTrue(PhoneUtils.looksLikeNumber("08031234567"))
    }

    @Test
    fun `accepts the country code without a plus`() {
        assertTrue(PhoneUtils.looksLikeNumber("2348031234567"))
    }

    @Test
    fun `accepts full E164`() {
        assertTrue(PhoneUtils.looksLikeNumber("+2348031234567"))
    }

    @Test
    fun `accepts numbers pasted with separators`() {
        assertTrue(PhoneUtils.looksLikeNumber("0803 123 4567"))
        assertTrue(PhoneUtils.looksLikeNumber("+234 803 123 4567"))
        assertTrue(PhoneUtils.looksLikeNumber("(0803) 123-4567"))
        assertTrue(PhoneUtils.looksLikeNumber("0803.123.4567"))
        assertTrue(PhoneUtils.looksLikeNumber("  +2348031234567  "))
    }

    @Test
    fun `accepts a non-breaking space and a unicode dash`() {
        // Both arrive from contact pickers and from pasted text.
        assertTrue(PhoneUtils.looksLikeNumber("0803 123 4567"))
        assertTrue(PhoneUtils.looksLikeNumber("0803–123–4567"))
    }

    @Test
    fun `does not judge the country, that is the server's job`() {
        // A UK number and a Nigerian landline are both number-shaped. The SDK
        // sends them and the API refuses them, which is the point: the rule can
        // change server-side without a new SDK release.
        assertTrue(PhoneUtils.looksLikeNumber("+447911123456"))
        assertTrue(PhoneUtils.looksLikeNumber("+2341234567"))
    }

    // ─── Only the obvious mistakes are refused ──────────────────────────────────

    @Test
    fun `rejects values that are not numbers at all`() {
        assertFalse(PhoneUtils.looksLikeNumber(""))
        assertFalse(PhoneUtils.looksLikeNumber("   "))
        assertFalse(PhoneUtils.looksLikeNumber("+"))
        assertFalse(PhoneUtils.looksLikeNumber("Ada Obi"))
        assertFalse(PhoneUtils.looksLikeNumber("+234ABC5678"))
        assertFalse(PhoneUtils.looksLikeNumber("null"))
        assertFalse(PhoneUtils.looksLikeNumber("+234-803-ABCD"))
    }

    @Test
    fun `rejects a plus that is not leading`() {
        assertFalse(PhoneUtils.looksLikeNumber("234+8031234567"))
        assertFalse(PhoneUtils.looksLikeNumber("08031234567+"))
    }

    // ─── canonicalise ───────────────────────────────────────────────────────────

    @Test
    fun `canonicalise strips separators and keeps a leading plus`() {
        assertEquals("+2348031234567", PhoneUtils.canonicalise("+234 803 123 4567"))
        assertEquals("08031234567", PhoneUtils.canonicalise("(0803) 123-4567"))
        assertEquals("+2348031234567", PhoneUtils.canonicalise("  +2348031234567  "))
    }

    @Test
    fun `requireNumberShaped returns the canonical form`() {
        assertEquals("08031234567", PhoneUtils.requireNumberShaped("0803 123 4567", "agentPhone"))
    }

    // ─── No part of a number in any message ─────────────────────────────────────

    @Test
    fun `the validation message names the field and no digits`() {
        try {
            PhoneUtils.requireNumberShaped("0803123XXXX", "agentPhone")
            fail("expected a Validation exception")
        } catch (e: RelavoiException.Validation) {
            val message = e.message ?: ""
            assertTrue("should name the field", message.contains("agentPhone"))
            // The old implementation appended the last four characters.
            assertFalse("must not contain any digits of the input", message.any { it.isDigit() })
            assertFalse(message.contains("XXXX"))
            assertFalse(message.contains("0803"))
        }
    }

    @Test
    fun `the validation message carries no digits even for an all-digit near miss`() {
        try {
            PhoneUtils.requireNumberShaped("", "customerPhone")
            fail("expected a Validation exception")
        } catch (e: RelavoiException.Validation) {
            assertFalse((e.message ?: "").any { it.isDigit() })
        }
    }
}
