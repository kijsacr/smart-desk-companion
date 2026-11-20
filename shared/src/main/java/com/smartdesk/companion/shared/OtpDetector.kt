package com.smartdesk.companion.shared

import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

enum class OtpChannel {
    SMS,
    WHATSAPP,
    GMAIL
}

data class OtpDetectionResult(
    val code: String,
    val formattedCode: String,
    val source: String,
    val score: Int
)

/**
 * V7 Section 4.2 & 4.3: Phone-side OTP Detector & Source Name Resolver.
 *
 * Rules:
 * 1. Must contain at least one case-insensitive OTP keyword.
 * 2. Must contain a standalone 4..8 digit code (including `123 456`, `123-456`, or `G-123456`).
 * 3. Scores each candidate based on proximity to keywords and penalizes currency amounts,
 *    time durations (`min`, `minutes`), percentages (`%`), and dates/years.
 * 4. Resolves the source name in strict priority order:
 *    (1) SMS sender ID -> (2) Gmail display name -> (3) WhatsApp chat title ->
 *    (4) Text pattern fallback -> (5) `Unknown`.
 */
object OtpDetector {

    private val OTP_KEYWORDS: List<String> = listOf(
        "one time password",
        "verification code",
        "authentication code",
        "security code",
        "login code",
        "one-time",
        "passcode",
        "code is",
        "do not share",
        "verify",
        "2fa",
        "otp"
    )

    // Standalone word-bounded keyword patterns for short keywords ("otp", "2fa")
    private val KEYWORD_REGEXES: List<Regex> = listOf(
        Regex("\\bone[\\s-]time(\\s+password)?\\b", RegexOption.IGNORE_CASE),
        Regex("\\bverification\\s+code\\b", RegexOption.IGNORE_CASE),
        Regex("\\bauthentication\\s+code\\b", RegexOption.IGNORE_CASE),
        Regex("\\bsecurity\\s+code\\b", RegexOption.IGNORE_CASE),
        Regex("\\blogin\\s+code\\b", RegexOption.IGNORE_CASE),
        Regex("\\bpasscode\\b", RegexOption.IGNORE_CASE),
        Regex("\\bcode\\s+is\\b", RegexOption.IGNORE_CASE),
        Regex("\\bdo\\s+not\\s+share\\b", RegexOption.IGNORE_CASE),
        Regex("\\bverify\\b", RegexOption.IGNORE_CASE),
        Regex("\\b2fa\\b", RegexOption.IGNORE_CASE),
        Regex("\\botp\\b", RegexOption.IGNORE_CASE)
    )

    /**
     * Matches:
     * - Google style `G-123456`
     * - Grouped 6-digit codes `123 456` or `123-456`
     * - Standalone 4 to 8 digit numbers
     */
    private val CANDIDATE_REGEX = Regex(
        "(?<![A-Za-z0-9.,₹$])(?:G-(\\d{4,8})|(\\d{3})[\\s-](\\d{3})|(\\d{4,8}))(?![A-Za-z0-9%]|\\.[0-9])",
        RegexOption.IGNORE_CASE
    )

    private val CURRENCY_BEFORE_REGEX = Regex(
        "(?:\\b(?:rs\\.?|inr|usd|amt|amount|paid|debited|credited|for)\\s*[:.]?\\s*|[₹$€£]\\s*)$",
        RegexOption.IGNORE_CASE
    )

    private val CURRENCY_AFTER_REGEX = Regex(
        "^\\s*(?:\\.\\d{1,2})?\\s*(?:rs\\b|inr\\b|/-)",
        RegexOption.IGNORE_CASE
    )

    private val UNIT_AFTER_REGEX = Regex(
        "^\\s*(?:min|mins|minute|minutes|sec|secs|second|seconds|hr|hrs|hour|hours|day|days|%|am|pm)\\b",
        RegexOption.IGNORE_CASE
    )

    private val GMAIL_NOISE_WORDS = Regex(
        "\\b(team|no-reply|noreply|do-not-reply|donotreply|account|accounts|security|support|info|alerts|alert|notification|notifications|verify|verification|mailer|official|service|services)\\b",
        RegexOption.IGNORE_CASE
    )

    private val BRAND_PATTERNS: List<Regex> = listOf(
        // "your <Brand> OTP" / "your <Brand> verification code" / "your <Brand> login code" / "your <Brand> code"
        Regex(
            "\\byour\\s+([A-Za-z][A-Za-z0-9&.'-]*(?:\\s+[A-Za-z][A-Za-z0-9&.'-]*)?)\\s+(?:otp|one[-\\s]time|verification\\s+code|security\\s+code|login\\s+code|authentication\\s+code|passcode|code)\\b",
            RegexOption.IGNORE_CASE
        ),
        // "<Brand> verification code" / "<Brand> security code" / "<Brand> login code" / "<Brand> OTP"
        Regex(
            "(?:^|[.:!\\n]\\s*)([A-Za-z][A-Za-z0-9&.'-]*(?:\\s+[A-Za-z][A-Za-z0-9&.'-]*)?)\\s+(?:verification\\s+code|security\\s+code|login\\s+code|authentication\\s+code|otp)\\b",
            RegexOption.IGNORE_CASE
        ),
        // "for <Brand> is" / "for <Brand>." / "for <Brand> account" / "for <Brand>"
        Regex(
            "\\b(?:otp|code|password|passcode)\\s+for\\s+(?:your\\s+)?([A-Za-z][A-Za-z0-9&.'-]*(?:\\s+[A-Za-z][A-Za-z0-9&.'-]*)?)\\b",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "\\bfor\\s+([A-Za-z][A-Za-z0-9&.'-]*(?:\\s+[A-Za-z][A-Za-z0-9&.'-]*)?)\\s+(?:login|account|verification|is\\b)",
            RegexOption.IGNORE_CASE
        )
    )

    private val INVALID_BRAND_STOPWORDS = setOf(
        "your", "the", "this", "that", "one", "time", "login", "signing", "sign",
        "in", "into", "account", "transaction", "payment", "order", "delivery",
        "mobile", "number", "email", "phone", "user", "customer", "dear", "use",
        "enter", "please", "never", "share", "valid", "online", "banking", "card",
        "netbanking", "upi", "resetting", "verifying"
    )

    private const val MIN_REQUIRED_SCORE = 25

    /**
     * Checks whether notification text is Android 15+'s redacted sensitive placeholder.
     */
    fun isRedactedSensitivePlaceholder(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val lower = text.trim().lowercase(Locale.US)
        return lower.contains("sensitive notification content hidden") ||
            lower == "sensitive content hidden" ||
            lower.contains("sensitive content hidden")
    }

    /**
     * Formats an OTP string into readable groups for the 20s tablet overlay:
     * - 6 digits `482913` -> `482 913`
     * - 8 digits `12345678` -> `1234 5678`
     * - 4, 5, 7 digits -> kept as-is (`1234`)
     */
    fun formatOtpForOverlay(rawCode: String): String {
        val digits = rawCode.filter { it.isDigit() }
        return when (digits.length) {
            6 -> "${digits.substring(0, 3)} ${digits.substring(3, 6)}"
            8 -> "${digits.substring(0, 4)} ${digits.substring(4, 8)}"
            else -> digits
        }
    }

    /**
     * Convenience overload accepting [senderOrHeader] and [bodyText].
     */
    fun detect(
        channel: OtpChannel,
        senderOrHeader: String?,
        bodyText: String
    ): OtpDetectionResult? = detect(
        channel = channel,
        messageText = bodyText,
        senderOrTitle = senderOrHeader,
        subjectText = null
    )

    /**
     * Attempts to detect an OTP in [messageText] (and optional [titleOrSubject]) and resolves
     * the source name according to Section 4.2.
     */
    fun detect(
        channel: OtpChannel,
        messageText: String,
        senderOrTitle: String? = null,
        subjectText: String? = null
    ): OtpDetectionResult? {
        val combinedText = buildString {
            if (!subjectText.isNullOrBlank()) {
                append(subjectText.trim()).append(". ")
            }
            append(messageText.trim())
        }.trim()

        if (combinedText.isEmpty()) return null

        // 1. Check for at least one OTP keyword
        val keywordSpans = mutableListOf<IntRange>()
        for (regex in KEYWORD_REGEXES) {
            for (match in regex.findAll(combinedText)) {
                keywordSpans.add(match.range)
            }
        }
        if (keywordSpans.isEmpty()) return null

        // 2. Find and score all 4..8 digit candidates
        var bestDigits: String? = null
        var bestScore = Int.MIN_VALUE

        for (match in CANDIDATE_REGEX.findAll(combinedText)) {
            val matchRange = match.range
            val gGoogle = match.groups[1]?.value
            val gSplit1 = match.groups[2]?.value
            val gSplit2 = match.groups[3]?.value
            val gPlain = match.groups[4]?.value

            val digits = when {
                gGoogle != null -> gGoogle
                gSplit1 != null && gSplit2 != null -> gSplit1 + gSplit2
                gPlain != null -> gPlain
                else -> continue
            }

            if (digits.length !in 4..8) continue

            // Check if part of a hyphen/slash date like 12/05/2026 or 2026-11-08
            val charBefore = combinedText.getOrNull(matchRange.first - 1)
            val charAfter = combinedText.getOrNull(matchRange.last + 1)
            if (charBefore == '/' || charAfter == '/') continue
            if ((charBefore == '-' || charAfter == '-') && gGoogle == null && gSplit1 == null) {
                continue
            }

            var score = 15

            // Length preference: 6-digit OTPs are standard (+15), 4 digit (+8), 5/7/8 (+5)
            score += when (digits.length) {
                6 -> 15
                4 -> 8
                else -> 5
            }

            // Google G-xxxxxx prefix is an explicit OTP marker
            if (gGoogle != null) {
                score += 45
            }

            // Distance to the nearest OTP keyword
            val minDistance = keywordSpans.minOf { kwRange ->
                when {
                    matchRange.first > kwRange.last -> matchRange.first - kwRange.last
                    kwRange.first > matchRange.last -> kwRange.first - matchRange.last
                    else -> 0
                }
            }

            if (minDistance <= 32) {
                score += 45
            } else if (minDistance <= 60) {
                score += 20
            }

            val prefixStart = (matchRange.first - 24).coerceAtLeast(0)
            val prefixWindow = combinedText.substring(prefixStart, matchRange.first)
            val suffixEnd = (matchRange.last + 1 + 20).coerceAtMost(combinedText.length)
            val suffixWindow = combinedText.substring(matchRange.last + 1, suffixEnd)

            // Bonus when directly preceded by "is ", ": ", "OTP ", "code "
            if (Regex("(?:is|otp|code|passcode|pin)\\s*[:=-]?\\s*$", RegexOption.IGNORE_CASE)
                    .containsMatchIn(prefixWindow)
            ) {
                score += 25
            }

            // Penalty: follows a currency marker (Rs, INR, ₹, $, Paid, Debited, etc.)
            if (CURRENCY_BEFORE_REGEX.containsMatchIn(prefixWindow) ||
                CURRENCY_AFTER_REGEX.containsMatchIn(suffixWindow)
            ) {
                score -= 120
            }

            // Penalty: precedes min, minutes, %, hrs, etc.
            if (UNIT_AFTER_REGEX.containsMatchIn(suffixWindow)) {
                score -= 100
            }

            // Penalty: preceded by "valid for", "expires in", "within", "ending", "xx", "a/c"
            if (Regex("(?:valid\\s+for|expires\\s+in|within|ending(?:\\s+in|\\s+with)?|a/c|acct|card|xx+|\\*\\*+)\\s*$", RegexOption.IGNORE_CASE)
                    .containsMatchIn(prefixWindow)
            ) {
                score -= 90
            }

            // Penalty: looks like a 4-digit year (1990..2039) unless right after "is " or "OTP "
            if (digits.length == 4) {
                val asInt = digits.toIntOrNull() ?: 0
                if (asInt in 1990..2039 &&
                    !Regex("(?:otp|code|pin|passcode)\\s+(?:is\\s+)?$", RegexOption.IGNORE_CASE)
                        .containsMatchIn(prefixWindow)
                ) {
                    score -= 40
                }
            }

            if (score > bestScore) {
                bestScore = score
                bestDigits = digits
            }
        }

        val finalCode = bestDigits ?: return null
        if (bestScore < MIN_REQUIRED_SCORE) return null

        val resolvedSource = resolveSourceName(
            channel = channel,
            senderOrTitle = senderOrTitle,
            messageText = combinedText,
             hadGooglePrefix = combinedText.contains("G-$finalCode", ignoreCase = true)
        )

        return OtpDetectionResult(
            code = finalCode,
            formattedCode = formatOtpForOverlay(finalCode),
            source = resolvedSource,
            score = bestScore
        )
    }

    /**
     * Section 4.2: Source name recognition applied in strict order:
     * 1. SMS sender ID
     * 2. Gmail sender display name
     * 3. WhatsApp chat title
     * 4. Text patterns as a fallback
     * 5. Otherwise `Unknown`
     */
    fun resolveSourceName(
        channel: OtpChannel,
        senderOrTitle: String?,
        messageText: String,
        hadGooglePrefix: Boolean = false
    ): String {
        when (channel) {
            OtpChannel.SMS -> {
                OtpSourceAliasCatalog.resolveSmsSenderId(senderOrTitle)?.let { return it }
            }

            OtpChannel.GMAIL -> {
                cleanGmailSenderName(senderOrTitle)?.let { return it }
            }

            OtpChannel.WHATSAPP -> {
                cleanWhatsAppTitle(senderOrTitle)?.let { return it }
            }
        }

        if (hadGooglePrefix) {
            return "Google"
        }

        extractBrandFromText(messageText)?.let { return it }

        return "Unknown"
    }

    private fun cleanGmailSenderName(rawSender: String?): String? {
        if (rawSender.isNullOrBlank()) return null
        // Strip email angle brackets if present, e.g. "Microsoft account team <account-security-noreply@accountprotection.microsoft.com>"
        val beforeBracket = rawSender.substringBefore("<").trim().trim('"', '\'')
        val cleaned = beforeBracket
            .replace(GMAIL_NOISE_WORDS, " ")
            .replace(Regex("[-_@.|]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (cleaned.isEmpty() || cleaned.equals("gmail", ignoreCase = true)) return null
        return OtpSourceAliasCatalog.toTitleCaseBrand(cleaned)
    }

    private fun cleanWhatsAppTitle(rawTitle: String?): String? {
        if (rawTitle.isNullOrBlank()) return null
        val cleaned = rawTitle.trim()
        if (cleaned.equals("WhatsApp", ignoreCase = true) ||
            cleaned.equals("Alert", ignoreCase = true) ||
            !cleaned.any { it.isLetter() }
        ) {
            return null
        }
        return OtpSourceAliasCatalog.toTitleCaseBrand(cleaned)
    }

    internal fun extractBrandFromText(text: String): String? {
        for (pattern in BRAND_PATTERNS) {
            for (match in pattern.findAll(text)) {
                val candidate = match.groupValues.getOrNull(1)?.trim().orEmpty()
                if (candidate.isEmpty()) continue
                val words = candidate.split(Regex("\\s+"))
                    .filter { w -> w.lowercase(Locale.US) !in INVALID_BRAND_STOPWORDS }
                if (words.isEmpty()) continue
                val brandRaw = words.take(2).joinToString(" ")
                if (brandRaw.length >= 2 && brandRaw.any { it.isLetter() }) {
                    return OtpSourceAliasCatalog.toTitleCaseBrand(brandRaw)
                }
            }
        }
        return null
    }
}
