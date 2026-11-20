package com.smartdesk.companion.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V7 Section 4.2: Unit tests for OTP detection and source name resolution across
 * bank, delivery, social, and email messages, including messages with currency amounts,
 * validity durations, and masked account numbers.
 */
class OtpDetectorTest {

    @Test
    fun `detects Swiggy SMS OTP and maps VM-SWIGGY-S sender ID`() {
        val result = OtpDetector.detect(
            channel = OtpChannel.SMS,
            senderOrTitle = "VM-SWIGGY-S",
            messageText = "482913 is your Swiggy verification code. Valid for 10 minutes. Do not share this OTP with anyone."
        )
        assertNotNull(result)
        assertEquals("482913", result!!.code)
        assertEquals("482 913", result.formattedCode)
        assertEquals("Swiggy", result.source)
    }

    @Test
    fun `picks OTP over currency amount and card digits in HDFC Bank SMS`() {
        val result = OtpDetector.detect(
            channel = OtpChannel.SMS,
            senderOrTitle = "AD-HDFCBK-S",
            messageText = "You have initiated a txn of INR 4599.00 on HDFC Bank Card ending 8821. OTP is 739204. Valid for 5 mins. Do not share."
        )
        assertNotNull(result)
        assertEquals("739204", result!!.code)
        assertEquals("739 204", result.formattedCode)
        assertEquals("HDFC Bank", result.source)
    }

    @Test
    fun `picks OTP when Rs amount appears before OTP`() {
        val result = OtpDetector.detect(
            channel = OtpChannel.SMS,
            senderOrTitle = "JX-ICICIB",
            messageText = "Rs 12500 debited Request. Use OTP 918273 to verify your payment within 10 minutes."
        )
        assertNotNull(result)
        assertEquals("918273", result!!.code)
        assertEquals("ICICI Bank", result.source)
    }

    @Test
    fun `detects Google style G-123456 OTP`() {
        val result = OtpDetector.detect(
            channel = OtpChannel.SMS,
            senderOrTitle = "TM-GOOGLE",
            messageText = "G-581029 is your Google verification code."
        )
        assertNotNull(result)
        assertEquals("581029", result!!.code)
        assertEquals("581 029", result.formattedCode)
        assertEquals("Google", result.source)
    }

    @Test
    fun `detects Gmail OTP and cleans sender display name`() {
        val result = OtpDetector.detect(
            channel = OtpChannel.GMAIL,
            senderOrTitle = "Microsoft account team",
            messageText = "Your security code is 640192. To help keep your account safe, do not share this code."
        )
        assertNotNull(result)
        assertEquals("640192", result!!.code)
        assertEquals("Microsoft", result.source)
    }

    @Test
    fun `detects WhatsApp business OTP with grouped digits`() {
        val result = OtpDetector.detect(
            channel = OtpChannel.WHATSAPP,
            senderOrTitle = "Instagram",
            messageText = "123 456 is your Instagram login code. Don't share it."
        )
        assertNotNull(result)
        assertEquals("123456", result!!.code)
        assertEquals("123 456", result.formattedCode)
        assertEquals("Instagram", result.source)
    }

    @Test
    fun `resolves brand from text fallback when sender is generic phone number`() {
        val result = OtpDetector.detect(
            channel = OtpChannel.SMS,
            senderOrTitle = "+919876543210",
            messageText = "Your Facebook verification code is 849201"
        )
        assertNotNull(result)
        assertEquals("849201", result!!.code)
        assertEquals("Facebook", result.source)
    }

    @Test
    fun `ignores normal chat or bank debit alert without OTP keyword`() {
        val debitOnly = OtpDetector.detect(
            channel = OtpChannel.SMS,
            senderOrTitle = "AD-HDFCBK",
            messageText = "Rs. 2500.00 debited from A/c XX4829 on 08-11-2026. Avl Bal INR 42190.00."
        )
        assertNull(debitOnly)
    }

    @Test
    fun `detects Android 15 sensitive notification redaction placeholder`() {
        assertTrue(OtpDetector.isRedactedSensitivePlaceholder("Sensitive notification content hidden"))
    }
}
