package org.telegram.tasks

import org.junit.Assert.assertEquals
import org.junit.Test


class BrandTextTest {

    @Test
    fun replacesPlainBrand() {
        assertEquals("ImpulseM Premium", BrandText.replace("Telegram Premium", "ImpulseM"))
    }


    @Test
    fun keepsTelegramTipsUrl() {
        assertEquals("https://t.me/TelegramTips", BrandText.replace("https://t.me/TelegramTips", "ImpulseM"))
        assertEquals("TelegramTipsRU", BrandText.replace("TelegramTipsRU", "ImpulseM"))
    }


    @Test
    fun keepsPlaceholderAndLinkMarkers() {
        assertEquals("{telegram}", BrandText.replace("{telegram}", "ImpulseM"))
        assertEquals("{Telegram}", BrandText.replace("{Telegram}", "ImpulseM"))
        assertEquals("[x](telegram)", BrandText.replace("[x](telegram)", "ImpulseM"))
    }


    @Test
    fun keepsTelegramDesktop() {
        assertEquals("telegramdesktop", BrandText.replace("telegramdesktop", "ImpulseM"))
        assertEquals("TelegramDesktop", BrandText.replace("TelegramDesktop", "ImpulseM"))
    }


    @Test
    fun keepsLowercaseHosts() {
        assertEquals("https://telegram.org/faq", BrandText.replace("https://telegram.org/faq", "ImpulseM"))
        assertEquals("telegram.org", BrandText.replace("telegram.org", "ImpulseM"))
    }


    @Test
    fun replacesAroundExclusions() {
        assertEquals(
            "ImpulseM {telegram} ImpulseM https://t.me/TelegramTips",
            BrandText.replace("Telegram {telegram} Telegram https://t.me/TelegramTips", "ImpulseM")
        )
    }


    @Test
    fun replacesArabicTransliteration() {
        assertEquals("مرحبا في ImpulseM", BrandText.replace("مرحبا في تيليجرام", "ImpulseM"))
        assertEquals("مرحبا في ImpulseM", BrandText.replace("مرحبا في تليجرام", "ImpulseM"))
    }


    @Test
    fun replacesKoreanTransliteration() {
        assertEquals("ImpulseM에 오신 것을 환영합니다", BrandText.replace("텔레그램에 오신 것을 환영합니다", "ImpulseM"))
    }


    @Test
    fun brandValueDrivesTheResult() {
        assertEquals("Xyz Premium", BrandText.replace("Telegram Premium", "Xyz"))
        assertEquals("Xyz", BrandText.replace("텔레그램", "Xyz"))
    }


    @Test
    fun brandIsInsertedLiterally() {
        assertEquals("A\$1 B", BrandText.replace("Telegram B", "A\$1"))
    }


    @Test
    fun replacesUppercaseWithUppercaseBrand() {
        assertEquals("INVITE TO IMPULSEM", BrandText.replace("INVITE TO TELEGRAM", "ImpulseM"))
        assertEquals("CONVIDAR PARA O XYZ", BrandText.replace("CONVIDAR PARA O TELEGRAM", "Xyz"))
    }


    @Test
    fun replacesCompoundWords() {
        assertEquals("Controleer je ImpulseMberichten", BrandText.replace("Controleer je Telegramberichten", "ImpulseM"))
    }


    @Test
    fun keepsHandlesAndUrlPaths() {
        assertEquals("write to @Telegram now", BrandText.replace("write to @Telegram now", "ImpulseM"))
        assertEquals("@TelegramBot", BrandText.replace("@TelegramBot", "ImpulseM"))
        assertEquals("https://t.me/Telegram", BrandText.replace("https://t.me/Telegram", "ImpulseM"))
        assertEquals("https://t.me/TelegramNews", BrandText.replace("https://t.me/TelegramNews", "ImpulseM"))
        assertEquals("Telegram.org", BrandText.replace("Telegram.org", "ImpulseM"))
    }


    @Test
    fun keepsTelegramTipsWhileReplacingNeighbours() {
        assertEquals("https://t.me/TelegramTipsDE", BrandText.replace("https://t.me/TelegramTipsDE", "ImpulseM"))
        assertEquals("ImpulseM https://t.me/TelegramTips ImpulseMberichten", BrandText.replace("Telegram https://t.me/TelegramTips Telegramberichten", "ImpulseM"))
    }


    @Test
    fun replacesBrandInsideMarkdownEmphasis() {
        assertEquals("by subscribing to __ImpulseM Premium.__", BrandText.replace("by subscribing to __Telegram Premium.__", "ImpulseM"))
    }


    @Test
    fun keepsCustomSchemeQuery() {
        assertEquals("tg://resolve?domain=Telegram", BrandText.replace("tg://resolve?domain=Telegram", "ImpulseM"))
    }


    @Test
    fun keepsHttpQueryValue() {
        assertEquals("https://example.com/?start=Telegram", BrandText.replace("https://example.com/?start=Telegram", "ImpulseM"))
    }


    @Test
    fun keepsHashtag() {
        assertEquals("#Telegram", BrandText.replace("#Telegram", "ImpulseM"))
    }


    @Test
    fun keepsHandleWithUnderscore() {
        assertEquals("@my_Telegram", BrandText.replace("@my_Telegram", "ImpulseM"))
    }


    @Test
    fun keepsUppercaseDomain() {
        assertEquals("TELEGRAM.ORG", BrandText.replace("TELEGRAM.ORG", "ImpulseM"))
    }


    @Test
    fun replacesBrandInMarkdownLinkText() {
        assertEquals(
            "[ImpulseM FAQ](https://telegram.org/faq)",
            BrandText.replace("[Telegram FAQ](https://telegram.org/faq)", "ImpulseM")
        )
    }


    @Test
    fun replacesAnchorTextAfterHttpHref() {
        assertEquals(
            "<a href=\"https://telegram.org/faq\">ImpulseM FAQ</a>",
            BrandText.replace("<a href=\"https://telegram.org/faq\">Telegram FAQ</a>", "ImpulseM")
        )
    }


    @Test
    fun replacesAnchorTextAfterCustomSchemeHref() {
        assertEquals(
            "<a href=\"tg://x\">ImpulseM</a>",
            BrandText.replace("<a href=\"tg://x\">Telegram</a>", "ImpulseM")
        )
    }


    @Test
    fun replacesMarkdownLinkTextWithTmeUrl() {
        assertEquals("[ImpulseM](https://t.me/x)", BrandText.replace("[Telegram](https://t.me/x)", "ImpulseM"))
    }


    @Test
    fun keepsUrlInParentheses() {
        assertEquals(
            "(see https://telegram.org/Telegram)",
            BrandText.replace("(see https://telegram.org/Telegram)", "ImpulseM")
        )
    }


    @Test
    fun keepsFormatStringPlaceholders() {
        assertEquals("%1\$s joined ImpulseM", BrandText.replace("%1\$s joined Telegram", "ImpulseM"))
    }
}
