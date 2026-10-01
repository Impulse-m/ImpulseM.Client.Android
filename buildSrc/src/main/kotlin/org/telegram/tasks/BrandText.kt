package org.telegram.tasks


/**
 * Replaces the upstream product name in user-visible localization VALUES.
 * Mirrors the backend language pack rule (tools/sticker-fetch/langpack-brand.mjs):
 * `\bTelegram\b` is case-sensitive, and four patterns are protected from the replacement.
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

    private val PATTERN: Regex = Regex(
        buildString {
            append("(")
            append(EXCLUSIONS.joinToString("|"))
            append(""")|(\bTelegram\b""")
            for (spelling in NATIVE_SPELLINGS) {
                append("|")
                append(Regex.escape(spelling))
            }
            append(")")
        }
    )


    fun replace(
        value: String,
        brand: String
    ): String {
        // A protected match (group 1) is returned unchanged, so an exclusion can never be rewritten.
        return PATTERN.replace(value) { match ->
            if (match.groups[1] != null) {
                match.value
            } else {
                brand
            }
        }
    }
}
