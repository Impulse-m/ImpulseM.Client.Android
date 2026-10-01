import re
import unittest
from pathlib import Path

import brand


# UI-visible attributes and element texts in which the brand may appear.
UI_ATTRIBUTE: re.Pattern[str] = re.compile(
    r'(?:android|app|tools):(?:title|label|summary|text|hint|description|contentDescription|shortcutShortLabel|shortcutLongLabel|dialogTitle|name)'
    r'\s*=\s*"[^"]*Telegram[^"]*"'
)
XML_TEXT: re.Pattern[str] = re.compile(r">[^<>]*Telegram[^<>]*<")

# Key format: "<path relative to TMessagesProj/src/main>|<offending fragment>" -> reason it stays.
ALLOWED: dict[str, str] = {
    "res/layout/call_notification.xml|tools:text=\"Telegram Call\"": "tools: attribute is design-time preview only, stripped from the built APK",
    "res/layout/call_notification_rtl.xml|tools:text=\"Telegram Call\"": "tools: attribute is design-time preview only, stripped from the built APK",
}


def scan_files() -> list[Path]:
    files: list[Path] = []
    for path in sorted(brand.RES.rglob("*.xml")):
        parts: tuple[str, ...] = path.relative_to(brand.RES).parts
        if parts[0].startswith("values") and path.name == "strings.xml":
            continue
        files.append(path)
    for path in sorted(brand.ASSETS.rglob("*")):
        if path.is_file() and path.suffix in (".html", ".json", ".txt", ".xml", ".js"):
            files.append(path)
    return files


def strip_comments(text: str) -> str:
    return re.sub(r"<!--.*?-->", "", text, flags=re.DOTALL)


class UiTextTest(unittest.TestCase):

    def test_no_unreviewed_visible_telegram(self) -> None:
        unexpected: list[str] = []
        for path in scan_files():
            text: str = strip_comments(brand.read(path))
            relative: str = path.relative_to(brand.MAIN).as_posix()
            fragments: list[str] = [m.group(0) for m in UI_ATTRIBUTE.finditer(text)]
            fragments += [m.group(0) for m in XML_TEXT.finditer(text)]
            for fragment in fragments:
                key: str = relative + "|" + fragment
                if key not in ALLOWED:
                    unexpected.append(key)
        self.assertEqual([], sorted(set(unexpected)))


    def test_allowed_entries_still_exist(self) -> None:
        stale: list[str] = []
        for key in ALLOWED:
            relative, fragment = key.split("|", 1)
            if fragment not in brand.read(brand.MAIN / relative):
                stale.append(key)
        self.assertEqual([], stale)


    def test_labels_use_generated_app_name(self) -> None:
        manifests: list[Path] = [brand.MAIN / "AndroidManifest.xml"]
        manifests += sorted((brand.ROOT / "TMessagesProj" / "config").rglob("AndroidManifest*.xml"))
        for manifest in manifests:
            for label in re.findall(r'android:label="([^"]*)"', brand.read(manifest)):
                self.assertTrue(label.startswith("@string/AppName"), manifest.name + ": " + label)
                self.assertNotIn("Telegram", label)


if __name__ == "__main__":
    unittest.main()
