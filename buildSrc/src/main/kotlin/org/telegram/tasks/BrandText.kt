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
        """TelegramTips\w*""",
        // Protected spans: URLs (scheme-anchored so "[Telegram](https://...)" link text is still rewritten), bare
        // t.me / telegram.me / telegram.dog links, www. hosts, @handles and #hashtags.
        """[A-Za-z][A-Za-z0-9+.\-]*://\S*""",
        """(?i)(?:t\.me|telegram\.me|telegram\.dog)/\S*""",
        """www\.\S*""",
        """@[\w.]+""",
        """#\w+"""
    )

    // A word start that is not part of an @handle, a URL path or query value, or a snake_case identifier.
    // An underscore only blocks when it joins two word parts (my_Telegram), so markdown emphasis (__Telegram) still works.
    private const val WORD_START: String = """(?<![@/=\p{L}\p{N}])(?<![\p{L}\p{N}]_)"""

    // Group 1: protected exclusion, group 2: "Telegram" (also as a word prefix), group 3: upper case, group 4: native spelling.
    private val PATTERN: Regex = Regex(
        buildString {
            append("(")
            append(EXCLUSIONS.joinToString("|"))
            append(")|(")
            append(WORD_START)
            append("""Telegram(?!\.(?:org|me|dog)\b))|(""")
            append(WORD_START)
            append("""TELEGRAM\b(?!\.\p{L}))|(""")
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
