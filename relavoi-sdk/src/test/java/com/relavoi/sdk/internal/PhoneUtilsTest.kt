package com.relavoi.sdk.internal

import com.relavoi.sdk.RelavoiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PhoneUtilsTest {

    @Test
    fun `validateE164 accepts valid Nigerian and US numbers`() {
        assertTrue(PhoneUtils.validateE164("+2348012345678"))
        assertTrue(PhoneUtils.validateE164("+12025550100"))
        assertTrue(PhoneUtils.validateE164("+447911123456")) // UK mobile
        assertTrue(PhoneUtils.validateE164("+2348022222222"))
    }

    @Test
    fun `validateE164 rejects malformed values`() {
        // Missing leading +
        assertFalse(PhoneUtils.validateE164("12345"))
        assertFalse(PhoneUtils.validateE164("2348012345678"))
        // Too short (< 7 digits after the country digit)
        assertFalse(PhoneUtils.validateE164("+12345"))
        // Leading zero in country code
        assertFalse(PhoneUtils.validateE164("+0123456789"))
        // Empty / nonsense
        assertFalse(PhoneUtils.validateE164(""))
        assertFalse(PhoneUtils.validateE164("+"))
        assertFalse(PhoneUtils.validateE164("+abc1234567"))
        // Too long (> 15 digits)
        assertFalse(PhoneUtils.validateE164("+1234567890123456"))
    }

    @Test
    fun `requireValidE164 throws Validation with last 4 digits and field name`() {
        try {
            PhoneUtils.requireValidE164("garbage1234", "agentPhone")
            fail("expected Validation exception")
        } catch (v: RelavoiException.Validation) {
            assertNotNull(v.message)
            assertTrue(
                "message should include field name; got=${v.message}",
                v.message!!.contains("agentPhone"),
            )
            assertTrue(
                "message should include last 4 digits; got=${v.message}",
                v.message!!.contains("1234"),
            )
            // CRITICAL: the message must NOT include the full input.
            assertFalse(
                "message must not contain full phone; got=${v.message}",
                v.message!!.contains("garbage1234"),
            )
        }
    }

    @Test
    fun `requireValidE164 passes silently on valid input`() {
        PhoneUtils.requireValidE164("+2348012345678", "agentPhone")
    }

    @Test
    fun `maskPhone returns plus stars last4 for normal numbers`() {
        assertEquals("+****5678", PhoneUtils.maskPhone("+2348012345678"))
        assertEquals("+****0100", PhoneUtils.maskPhone("+12025550100"))
    }

    @Test
    fun `maskPhone handles very short input safely`() {
        assertEquals("+****", PhoneUtils.maskPhone(""))
        assertEquals("+****", PhoneUtils.maskPhone("+12"))
    }
}
