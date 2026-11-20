package com.smartdesk.companion.shared

import java.util.Locale

/**
 * V7 Section 4.2: Editable SMS Sender ID & Brand Alias Table.
 * Strips operator prefixes/suffixes (e.g., `VM-SWIGGY-S` -> `SWIGGY`)
 * and maps known sender codes to clean display names.
 */
object OtpSourceAliasCatalog {

    val SENDER_ID_ALIASES: Map<String, String> = mapOf(
        "HDFCBK" to "HDFC Bank",
        "HDFCBN" to "HDFC Bank",
        "ICICIB" to "ICICI Bank",
        "ICICBK" to "ICICI Bank",
        "SBIPSG" to "SBI",
        "SBININ" to "SBI",
        "SBIBNK" to "SBI",
        "AXISBK" to "Axis Bank",
        "KOTAKB" to "Kotak Bank",
        "INDUSB" to "IndusInd Bank",
        "YESBNK" to "Yes Bank",
        "IDFCFB" to "IDFC First Bank",
        "AMZNIN" to "Amazon",
        "AMAZON" to "Amazon",
        "MSFT" to "Microsoft",
        "MICRSF" to "Microsoft",
        "FBOOK" to "Facebook",
        "FACEBK" to "Facebook",
        "INSTGM" to "Instagram",
        "INSTAG" to "Instagram",
        "GOOGLE" to "Google",
        "GOOG" to "Google",
        "ZOMATO" to "Zomato",
        "SWIGGY" to "Swiggy",
        "PAYTM" to "Paytm",
        "PAYTMB" to "Paytm",
        "PHONEPE" to "PhonePe",
        "PHONPE" to "PhonePe",
        "UBERIN" to "Uber",
        "UBER" to "Uber",
        "OLACAB" to "Ola",
        "FLIPKT" to "Flipkart",
        "FLPKRT" to "Flipkart",
        "MYNTRA" to "Myntra",
        "BLINKT" to "Blinkit",
        "ZEPTO" to "Zepto",
        "BIGBSK" to "BigBasket",
        "IRCTCI" to "IRCTC",
        "IRCTC" to "IRCTC",
        "UIDAI" to "Aadhaar",
        "AADHAR" to "Aadhaar",
        "NETFLX" to "Netflix",
        "STEAM" to "Steam",
        "DISCRD" to "Discord",
        "GITHUB" to "GitHub",
        "APPLE" to "Apple",
        "WHTSAP" to "WhatsApp",
        "JIOMRT" to "JioMart",
        "JIOINF" to "Jio",
        "AIRTEL" to "Airtel",
        "CREDCL" to "CRED"
    )

    private val PRESERVED_CASED_BRANDS: Map<String, String> = mapOf(
        "hdfc" to "HDFC Bank",
        "hdfc bank" to "HDFC Bank",
        "icici" to "ICICI Bank",
        "icici bank" to "ICICI Bank",
        "sbi" to "SBI",
        "irctc" to "IRCTC",
        "phonepe" to "PhonePe",
        "paytm" to "Paytm",
        "github" to "GitHub",
        "whatsapp" to "WhatsApp",
        "youtube" to "YouTube",
        "linkedin" to "LinkedIn",
        "cred" to "CRED"
    )

    /**
     * Strips Indian DLT/operator prefixes and suffixes from an SMS sender address
     * (for example `VM-SWIGGY-S` -> `SWIGGY`, `AD-HDFCBK` -> `HDFCBK`, `JX-612345` -> `612345`)
     * and resolves it through [SENDER_ID_ALIASES] or converts to Title Case.
     */
    fun resolveSmsSenderId(rawSender: String?): String? {
        if (rawSender.isNullOrBlank()) return null
        val trimmed = rawSender.trim()

        // Ignore plain phone numbers (only digits, +, spaces, hyphens without letters)
        if (!trimmed.any { it.isLetter() }) return null

        val upper = trimmed.uppercase(Locale.US)
        // Strip leading 2-3 char DLT header like "VM-", "AD-", "JX-", "AX-", "TM-", "BP-"
        val withoutPrefix = upper.replace(Regex("^[A-Z]{2,3}-"), "")
        // Strip trailing DLT category suffix like "-S", "-T", "-P", "-G"
        val core = withoutPrefix.replace(Regex("-[A-Z]$"), "")
            .replace(Regex("[^A-Z0-9 ]"), "")
            .trim()

        if (core.isEmpty() || !core.any { it.isLetter() }) return null

        SENDER_ID_ALIASES[core]?.let { return it }

        // Check if any alias key is a prefix/substring of core (e.g. "PHONEPE" in "PHONEP")
        for ((key, mapped) in SENDER_ID_ALIASES) {
            if (core.equals(key, ignoreCase = true)) {
                return mapped
            }
        }

        return toTitleCaseBrand(core)
    }

    /**
     * Converts a raw brand/source string to clean Title Case while preserving
     * well-known brand casing (like `HDFC Bank`, `PhonePe`, `GitHub`).
     */
    fun toTitleCaseBrand(raw: String): String {
        val cleaned = raw.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isEmpty()) return "Unknown"

        val lower = cleaned.lowercase(Locale.US)
        PRESERVED_CASED_BRANDS[lower]?.let { return it }
        SENDER_ID_ALIASES[cleaned.uppercase(Locale.US)]?.let { return it }

        return cleaned.split(" ").joinToString(" ") { word ->
            val wLower = word.lowercase(Locale.US)
            PRESERVED_CASED_BRANDS[wLower] ?: wLower.replaceFirstChar { ch ->
                if (ch.isLowerCase()) ch.titlecase(Locale.US) else ch.toString()
            }
        }
    }
}
