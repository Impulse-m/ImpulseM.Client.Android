package org.telegram.tasks

import java.util.Locale


/**
 * Replaces the upstream product name in user-visible localization VALUES.
 * Extends the backend language pack rule (tools/sticker-fetch/langpack-brand.mjs): "Telegram" at a word start
 * (also as a compound prefix, e.g. Telegramberichten) becomes the brand, TELEGRAM the upper-case brand, native-script
 * spellings the brand. @handles, URL paths and the four backend exclusions are protected.
 */
object BrandText {

    /** Spellings of the product name in the bundled non-Latin locales. */
    val NATIVE_SPELLINGS: List<String> = listOf(
        "تيليجرام",
        "تليجرام",
        "텔레그램"
    )

    private val EXCLUSIONS: List<String> = listOf(
        """(?i)\{telegram\}""",
        """(?i)\]\(telegram\)""",
        """(?i)telegramdesktop""",
        """TelegramTips\w*"""
    )

    // A word start that is not part of an @handle or a URL path.
    private const val WORD_START: String = """(?<![@/\p{L}\p{N}])"""

    // Group 1: protected exclusion, group 2: "Telegram" (also as a word prefix), group 3: upper case, group 4: native spelling.
    private val PATTERN: Regex = Regex(
        buildString {
            append("(")
            append(EXCLUSIONS.joinToString("|"))
            append(")|(")
            append(WORD_START)
            append("""Telegram(?!\.(?:org|me|dog)\b))|(""")
            append(WORD_START)
            append("""TELEGRAM\b)|(""")
            append(NATIVE_SPELLINGS.joinToString("|") { Regex.escape(it) })
            append(")")
        }
    )


    fun replace(
        value: String,
        brand: String
    ): String {
        // A protected match (group 1) is returned unchanged, so an exclusion can never be rewritten.
        return PATTERN.replace(value) { match ->
            when {
                match.groups[1] != null -> match.value
                match.groups[3] != null -> brand.uppercase(Locale.ROOT)
                else -> brand
            }
        }
    }
}
