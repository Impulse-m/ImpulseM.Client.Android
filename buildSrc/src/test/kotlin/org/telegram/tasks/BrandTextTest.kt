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
}
