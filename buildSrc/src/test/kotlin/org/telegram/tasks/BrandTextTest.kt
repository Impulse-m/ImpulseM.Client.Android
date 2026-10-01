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
}
