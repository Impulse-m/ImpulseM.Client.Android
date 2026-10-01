import re
import unittest
from pathlib import Path

import brand
import recolor


THEME_COLORS: Path = brand.JAVA / "org" / "telegram" / "ui" / "ActionBar" / "ThemeColors.java"
THEME_JAVA: Path = brand.JAVA / "org" / "telegram" / "ui" / "ActionBar" / "Theme.java"
THEME_NAMES: list[str] = ["Blue", "Dark Blue", "Arctic Blue", "Day", "Night"]


def attheme(name: str) -> dict[str, int]:
    values: dict[str, int] = {}
    for line in brand.read(brand.ASSETS / name).splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            if re.fullmatch(r"-?\d+", value.strip()):
                values[key.strip()] = brand.attheme_to_argb(int(value.strip()))
    return values


def split_top_level(arguments: str) -> list[str]:
    parts: list[str] = []
    depth: int = 0
    current: str = ""
    for char in arguments:
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
        if char == "," and depth == 0:
            parts.append(current.strip())
            current = ""
        else:
            current += char
    parts.append(current.strip())
    return parts


def accents(theme: str) -> dict[int, int]:
    """Returns accent id -> accent color for a built-in theme, parsed from setAccentColorOptions."""
    java: str = brand.read(THEME_JAVA)
    start: int = java.index('themeInfo.name = "' + theme + '";')
    call: int = java.index("themeInfo.setAccentColorOptions(", start) + len("themeInfo.setAccentColorOptions(")
    end: int = java.index(");", call)
    arguments: list[str] = split_top_level(java[call:end])
    colors: list[int] = [int(x, 0) & 0xFFFFFFFF for x in re.findall(r"0x[0-9a-fA-F]+|\b\d+\b", arguments[0].split("{", 1)[1])]
    ids: list[int] = [int(x) for x in re.findall(r"-?\d+", arguments[7].split("{", 1)[1])]
    return dict(zip(ids, colors))


class ColorsTest(unittest.TestCase):

    def test_shift_maps_base_to_target(self) -> None:
        self.assertEqual(brand.PRIMARY_DAY, recolor.shift(recolor.TELEGRAM_DAY_BLUE, recolor.TELEGRAM_DAY_BLUE, brand.PRIMARY_DAY))
        self.assertEqual(brand.PRIMARY_NIGHT, recolor.shift(recolor.TELEGRAM_NIGHT_BLUE, recolor.TELEGRAM_NIGHT_BLUE, brand.PRIMARY_NIGHT))


    def test_generated_files_match_script(self) -> None:
        for name in recolor.DAY_THEMES + recolor.NIGHT_THEMES:
            expected: str = recolor.transform_attheme(name, brand.upstream(brand.ASSETS / name))
            self.assertEqual(expected, brand.read(brand.ASSETS / name).replace("\r\n", "\n"), name)
        expected_java: str = recolor.transform_theme_colors(brand.upstream(THEME_COLORS))
        self.assertEqual(expected_java, brand.read(THEME_COLORS).replace("\r\n", "\n"))


    def test_brand_constants(self) -> None:
        java: str = brand.read(THEME_COLORS)
        self.assertIn("public static final int TELEGRAM_COLOR = 0xFF2085DF;", java)
        self.assertIn("public static final int TELEGRAM_COLOR_TEXT = 0xFF106ACC;", java)


    def test_day_theme_sentinels(self) -> None:
        blue: dict[str, int] = attheme("bluebubbles.attheme")
        self.assertEqual(brand.PRIMARY_DAY, blue["telegram_color"])
        self.assertEqual(brand.LINK_DAY, blue["key_telegram_color_text"])
        self.assertEqual(brand.attheme_to_argb(-1641732), blue["chat_outBubble"], "bubbles stay Telegram's")
        self.assertEqual(0xFF69BDF9, blue["avatar_backgroundSaved"], "avatar palette stays Telegram's")


    def test_night_theme_sentinels(self) -> None:
        night: dict[str, int] = attheme("night.attheme")
        self.assertEqual(brand.NIGHT_SURFACE, night["windowBackgroundWhite"])
        self.assertEqual(brand.NIGHT_SURFACE, night["actionBarDefault"])
        self.assertEqual(brand.NIGHT_BACKGROUND, night["windowBackgroundGray"])
        self.assertEqual(brand.NIGHT_DIVIDER, night["divider"])
        self.assertEqual(brand.PRIMARY_NIGHT, night["dialogTextLink"])
        self.assertEqual(0xFF366CAF, night["chat_outBubble"], "bubbles stay Telegram's")


    def test_accent_bases(self) -> None:
        self.assertEqual(brand.PRIMARY_DAY, accents("Blue")[99])
        self.assertEqual(brand.PRIMARY_NIGHT, accents("Night")[0])
        for theme in THEME_NAMES:
            self.assertEqual(recolor.accent_base(theme), accents(theme)[0], theme)


    def test_night_is_default_night_theme(self) -> None:
        java: str = brand.read(THEME_JAVA)
        self.assertIn('themesDict.put("Night", currentNightTheme = themeInfo);', java)
        self.assertIn('themesDict.put("Dark Blue", themeInfo);', java)
        self.assertNotIn('currentNightTheme = themesDict.get("Dark Blue")', java)
        self.assertNotIn("currentNightTheme = themeDarkBlue", java)


    def test_xml_brand_colors(self) -> None:
        for folder in ("values", "values-v21", "values-v31"):
            styles: str = brand.read(brand.RES / folder / "styles.xml")
            self.assertNotIn("527da3", styles.lower(), folder)
            self.assertNotIn("426482", styles.lower(), folder)
        self.assertNotIn("#1f2732", brand.read(brand.RES / "values-night" / "styles.xml").lower())


if __name__ == "__main__":
    unittest.main()
