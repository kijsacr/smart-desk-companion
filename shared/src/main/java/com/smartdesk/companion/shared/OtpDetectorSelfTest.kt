package com.smartdesk.companion.shared

/**
 * V7 Section 4.2: Self-contained unit test suite for [OtpDetector] covering
 * typical bank, delivery, social, and email OTP texts, including ones with currency amounts,
 * validity durations, and masked card numbers.
 */
object OtpDetectorSelfTest {

    data class TestCase(
        val name: String,
        val channel: OtpChannel,
        val senderOrTitle: String?,
        val messageText: String,
        val expectedCode: String?,
        val expectedSource: String?
    )

    val TEST_SUITE: List<TestCase> = listOf(
        TestCase(
            name = "Swiggy delivery SMS with DLT header VM-SWIGGY-S and validity minutes",
            channel = OtpChannel.SMS,
            senderOrTitle = "VM-SWIGGY-S",
            messageText = "482913 is your Swiggy verification code. Valid for 10 minutes. Do not share this OTP with anyone.",
            expectedCode = "482913",
            expectedSource = "Swiggy"
        ),
        TestCase(
            name = "HDFC Bank SMS with INR amount, masked card digits, and validity minutes",
            channel = OtpChannel.SMS,
            senderOrTitle = "AD-HDFCBK-S",
            messageText = "You have initiated a txn of INR 4599.00 on HDFC Bank Card ending 8821. OTP is 739204. Valid for 5 mins. Do not share.",
            expectedCode = "739204",
            expectedSource = "HDFC Bank"
        ),
        TestCase(
            name = "ICICI Bank SMS with Rs amount before OTP",
            channel = OtpChannel.SMS,
            senderOrTitle = "JX-ICICIB",
            messageText = "Rs 12500 debited Request. Use OTP 918273 to verify your payment within 10 minutes.",
            expectedCode = "918273",
            expectedSource = "ICICI Bank"
        ),
        TestCase(
            name = "Amazon SMS with Rupee symbol ₹ amount and OTP",
            channel = OtpChannel.SMS,
            senderOrTitle = "VM-AMZNIN-S",
            messageText = "₹1499.00 order verification: 394810 is your Amazon OTP. Do not share it with anyone.",
            expectedCode = "394810",
            expectedSource = "Amazon"
        ),
        TestCase(
            name = "Google G-xxxxxx verification code SMS",
            channel = OtpChannel.SMS,
            senderOrTitle = "TM-GOOGLE",
            messageText = "G-581029 is your Google verification code.",
            expectedCode = "581029",
            expectedSource = "Google"
        ),
        TestCase(
            name = "Microsoft Gmail security code with sender noise words stripped",
            channel = OtpChannel.GMAIL,
            senderOrTitle = "Microsoft account team",
            messageText = "Your security code is 640192. To help keep your account safe, do not share this code.",
            expectedCode = "640192",
            expectedSource = "Microsoft"
        ),
        TestCase(
            name = "Instagram WhatsApp business login code with grouped 3+3 digits",
            channel = OtpChannel.WHATSAPP,
            senderOrTitle = "Instagram",
            messageText = "123 456 is your Instagram login code. Don't share it.",
            expectedCode = "123456",
            expectedSource = "Instagram"
        ),
        TestCase(
            name = "Facebook text pattern fallback when sender is a phone number",
            channel = OtpChannel.SMS,
            senderOrTitle = "+919876543210",
            messageText = "Your Facebook verification code is 849201",
            expectedCode = "849201",
            expectedSource = "Facebook"
        ),
        TestCase(
            name = "Non-OTP bank debit alert with amounts and account numbers is ignored",
            channel = OtpChannel.SMS,
            senderOrTitle = "AD-HDFCBK",
            messageText = "Rs. 2500.00 debited from A/c XX4829 on 08-11-2026. Avl Bal INR 42190.00.",
            expectedCode = null,
            expectedSource = null
        )
    )

    /**
     * Runs all OTP detection unit tests and returns `(passedCount, totalCount, failureMessages)`.
     */
    fun runAll(): Triple<Int, Int, List<String>> {
        var passed = 0
        val failures = mutableListOf<String>()
        for (tc in TEST_SUITE) {
            val res = OtpDetector.detect(
                channel = tc.channel,
                messageText = tc.messageText,
                senderOrTitle = tc.senderOrTitle
            )
            val actualCode = res?.code
            val actualSource = res?.source
            if (actualCode == tc.expectedCode && actualSource == tc.expectedSource) {
                passed++
            } else {
                failures.add(
                    "${tc.name}: expected (${tc.expectedSource}, ${tc.expectedCode}) but got ($actualSource, $actualCode)"
                )
            }
        }
        return Triple(passed, TEST_SUITE.size, failures)
    }
}
