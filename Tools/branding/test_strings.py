import re
import unittest

import brand


LITERAL: re.Pattern[str] = re.compile(r'"((?:[^"\\\n]|\\.)*Telegram(?:[^"\\\n]|\\.)*)"')

# Every remaining Java string literal containing "Telegram" must be listed here with the reason it stays.
# Key format: "<path relative to TMessagesProj/src/main/java>|<literal without quotes>".
LANGPACK_KEY: str = "language pack resource key, not displayed text; the shown value comes from the backend language pack"
JS_BRIDGE: str = "JS bridge name that bots and web apps call by this exact name; renaming breaks their API"
JS_EVENT: str = "JS call into the web apps' window.Telegram.WebView API, defined by the Bot API"

ALLOWED: dict[str, str] = {
    "org/telegram/messenger/AndroidUtilities.java|TelegramVersion": LANGPACK_KEY,
    "org/telegram/ui/BoostsActivity.java|BoostingTelegramPremiumCountPlural": LANGPACK_KEY,
    "org/telegram/ui/ChannelBoostLayout.java|BoostingTelegramPremiumCountPlural": LANGPACK_KEY,
    "org/telegram/ui/Cells/InviteUserCell.java|TelegramContacts": LANGPACK_KEY,
    "org/telegram/ui/Components/Premium/boosts/cells/TableCell.java|BoostingTelegramPremiumFor": LANGPACK_KEY,
    "org/telegram/ui/Components/StorageUsageView.java|TelegramCacheSize": LANGPACK_KEY,
    "org/telegram/ui/PrivacySettingsActivity.java|TelegramPassport": LANGPACK_KEY,
    "org/telegram/ui/PrivacySettingsActivity.java|MapPreviewProviderTelegram": LANGPACK_KEY,
    "org/telegram/messenger/TelegramMediaSession.java|TelegramMediaSession": "media session tag, an internal identifier never shown to the user",
    "org/telegram/ui/ArticleViewer.java|TelegramWebviewProxy": JS_BRIDGE,
    "org/telegram/ui/PaymentFormActivity.java|TelegramWebviewProxy": JS_BRIDGE,
    "org/telegram/ui/WebviewActivity.java|TelegramWebviewProxy": JS_BRIDGE,
    "org/telegram/ui/web/BotWebViewContainer.java|TelegramWebviewProxy": JS_BRIDGE,
    "org/telegram/ui/web/BotWebViewContainer.java|TelegramWebviewProxyMessage": "name of the WebMessage bridge paired with the TelegramWebviewProxy JS API",
    "org/telegram/ui/web/BotWebViewContainer.java|window.TelegramWebviewProxy={postEvent:function(eventType,eventData){": JS_BRIDGE,
    "org/telegram/ui/web/BotWebViewContainer.java| Telegram-Android/": "User-Agent token that bots and web apps sniff to detect the Telegram Android client; not shown in the UI",
    "org/telegram/ui/web/BotWebViewContainer.java|window.Telegram.WebView.receiveEvent('": JS_EVENT,
    "org/telegram/ui/bots/BotSensors.java|window.Telegram.WebView.receiveEvent('": JS_EVENT,
    "org/telegram/utils/proxy/WebProxyTransport.java|TelegramWebProxy": "JS bridge object name used by the web proxy transport; renaming breaks the bridge",
}


class StringsTest(unittest.TestCase):

    def test_no_unreviewed_telegram_literals(self) -> None:
        unexpected: list[str] = []
        for path in sorted(brand.JAVA.rglob("*.java")):
            relative: str = path.relative_to(brand.JAVA).as_posix()
            for match in LITERAL.finditer(brand.read(path)):
                key: str = relative + "|" + match.group(1)
                if key not in ALLOWED:
                    unexpected.append(key)
        self.assertEqual([], sorted(set(unexpected)))


    def test_allowed_entries_still_exist(self) -> None:
        stale: list[str] = []
        for key in ALLOWED:
            relative, literal = key.split("|", 1)
            if '"' + literal + '"' not in brand.read(brand.JAVA / relative):
                stale.append(key)
        self.assertEqual([], stale)


if __name__ == "__main__":
    unittest.main()
