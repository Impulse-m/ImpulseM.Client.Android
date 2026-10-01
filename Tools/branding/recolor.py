"""Re-seeds Telegram's blue to the ImpulseM palette.

Outputs (generated, do not hand-edit; change this script and re-run):
- TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ThemeColors.java
- TMessagesProj/src/main/assets/{bluebubbles,day,arctic,darkblue,night}.attheme

Each output is computed from the upstream Telegram file at brand.UPSTREAM_COMMIT, so re-running is idempotent.
"""

import colorsys
import re
import sys
from pathlib import Path

import brand


TELEGRAM_DAY_BLUE: int = 0xFF229AF0
TELEGRAM_DAY_TEXT: int = 0xFF298ACF
TELEGRAM_NIGHT_BLUE: int = 0xFF64B5EF
TELEGRAM_NIGHT_SURFACE: int = 0xFF181819
DAY_THEMES: list[str] = ["bluebubbles.attheme", "day.attheme", "arctic.attheme"]
NIGHT_THEMES: list[str] = ["night.attheme", "darkblue.attheme"]
EXCLUDED_KEY_PREFIXES: list[str] = [
    "chat_in",
    "chat_out",
    "chat_wallpaper",
    "avatar_",
    "stories_",
    "statisticChart",
    "color_",
    "premium",
    "gift",
    "code_",
]
EXACT_DAY: dict[str, int] = {
    "telegram_color": brand.PRIMARY_DAY,
    "key_telegram_color_text": brand.LINK_DAY,
}
NIGHT_SURFACE_KEYS: list[str] = ["windowBackgroundWhite", "actionBarDefault", "actionBarDefaultArchived", "chats_menuBackground"]
NIGHT_BACKGROUND_KEYS: list[str] = ["windowBackgroundGray", "iv_background", "iv_navigationBackground", "chat_wallpaper"]
NIGHT_DIVIDER_KEYS: list[str] = ["divider"]
UPSTREAM_ACCENT_BASES: dict[str, int] = {
    "Blue": 0xFF328ACF,
    "Day": 0xFF4C91DF,
    "Arctic Blue": 0xFF3490EB,
    "Dark Blue": 0xFF3685FA,
    "Night": 0xFF3E88F7,
}
THEME_COLORS: Path = brand.JAVA / "org" / "telegram" / "ui" / "ActionBar" / "ThemeColors.java"
DEFAULT_COLOR_LINE: re.Pattern[str] = re.compile(r"^(\s*defaultColors\[key_(\w+)\] = )(0x[0-9a-fA-F]{8})(;.*)$")


def hsv(color: int) -> tuple[float, float, float]:
    return colorsys.rgb_to_hsv(((color >> 16) & 0xFF) / 255, ((color >> 8) & 0xFF) / 255, (color & 0xFF) / 255)


def shift(color: int, base: int, target: int) -> int:
    """Moves a color by the HSV offset between base and target, keeping alpha. base maps exactly onto target."""
    if (color & 0xFFFFFF) == (base & 0xFFFFFF):
        return (color & 0xFF000000) | (target & 0xFFFFFF)
    h, s, v = hsv(color)
    bh, bs, bv = hsv(base)
    th, ts, tv = hsv(target)
    r, g, b = colorsys.hsv_to_rgb((h + th - bh) % 1.0, min(1.0, s * ts / bs), min(1.0, v * tv / bv))
    return (color & 0xFF000000) | (round(r * 255) << 16) | (round(g * 255) << 8) | round(b * 255)


def is_blue(color: int) -> bool:
    h, s, v = hsv(color)
    return 190 / 360 <= h <= 222 / 360 and s >= 0.35 and v >= 0.40


def is_excluded(key: str) -> bool:
    name: str = key[4:] if key.startswith("key_") else key
    return any(name.startswith(prefix) for prefix in EXCLUDED_KEY_PREFIXES)


def is_dark_neutral(color: int) -> bool:
    h, s, v = hsv(color)
    return (color >> 24) == 0xFF and s < 0.15 and 0.03 < v < 0.30


def tint(color: int) -> int:
    channels: list[int] = []
    for shift_bits in (16, 8, 0):
        delta: int = ((brand.NIGHT_SURFACE >> shift_bits) & 0xFF) - ((TELEGRAM_NIGHT_SURFACE >> shift_bits) & 0xFF)
        channels.append(max(0, min(255, ((color >> shift_bits) & 0xFF) + delta)))
    return 0xFF000000 | (channels[0] << 16) | (channels[1] << 8) | channels[2]


def recolor_value(name: str, key: str, color: int) -> int:
    night: bool = name in NIGHT_THEMES
    if not night and key in EXACT_DAY:
        return EXACT_DAY[key]
    if name == "night.attheme":
        if key in NIGHT_SURFACE_KEYS:
            return brand.NIGHT_SURFACE
        if key in NIGHT_BACKGROUND_KEYS:
            return brand.NIGHT_BACKGROUND
        if key in NIGHT_DIVIDER_KEYS:
            return brand.NIGHT_DIVIDER
    if is_excluded(key):
        return color
    if is_blue(color):
        if night:
            return shift(color, TELEGRAM_NIGHT_BLUE, brand.PRIMARY_NIGHT)
        return shift(color, TELEGRAM_DAY_BLUE, brand.PRIMARY_DAY)
    if name == "night.attheme" and is_dark_neutral(color):
        return tint(color)
    return color


def transform_attheme(name: str, text: str) -> str:
    lines: list[str] = []
    for line in text.split("\n"):
        match: re.Match[str] | None = re.fullmatch(r"([^=\s]+)=(-?\d+)", line.strip())
        if match is None:
            lines.append(line)
            continue
        key: str = match.group(1)
        color: int = brand.attheme_to_argb(int(match.group(2)))
        lines.append(key + "=" + str(brand.argb_to_attheme(recolor_value(name, key, color))))
    return "\n".join(lines)


def transform_theme_colors(text: str) -> str:
    text = text.replace("TELEGRAM_COLOR = 0xFF229AF0;        // -14509328", "TELEGRAM_COLOR = 0xFF2085DF;        // " + str(brand.argb_to_attheme(brand.PRIMARY_DAY)))
    text = text.replace("TELEGRAM_COLOR_TEXT = 0xFF298ACF;   // -14054705", "TELEGRAM_COLOR_TEXT = 0xFF106ACC;   // " + str(brand.argb_to_attheme(brand.LINK_DAY)))
    lines: list[str] = []
    for line in text.split("\n"):
        match: re.Match[str] | None = DEFAULT_COLOR_LINE.match(line)
        if match is None:
            lines.append(line)
            continue
        key: str = match.group(2)
        color: int = int(match.group(3), 16)
        if is_excluded(key) or not is_blue(color):
            lines.append(line)
            continue
        target: int = brand.LINK_DAY if ("Link" in key or key.endswith("Text3")) else brand.PRIMARY_DAY
        new_color: int = shift(color, TELEGRAM_DAY_TEXT if target == brand.LINK_DAY else TELEGRAM_DAY_BLUE, target)
        lines.append(match.group(1) + "0x{:08x}".format(new_color) + match.group(4))
    return "\n".join(lines)


def accent_base(theme: str) -> int:
    """The id-0 accent color each built-in theme must carry so Telegram's accent math starts from the brand family."""
    if theme == "Night":
        return brand.PRIMARY_NIGHT
    upstream: int = UPSTREAM_ACCENT_BASES[theme]
    if theme == "Dark Blue":
        return shift(upstream, TELEGRAM_NIGHT_BLUE, brand.PRIMARY_NIGHT)
    return shift(upstream, TELEGRAM_DAY_BLUE, brand.PRIMARY_DAY)


def main() -> None:
    for name in DAY_THEMES + NIGHT_THEMES:
        path: Path = brand.ASSETS / name
        path.write_text(transform_attheme(name, brand.upstream(path)), encoding="utf-8", newline="\n")
    THEME_COLORS.write_text(transform_theme_colors(brand.upstream(THEME_COLORS)), encoding="utf-8", newline="\n")
    if "--print-accents" in sys.argv:
        for theme in UPSTREAM_ACCENT_BASES:
            print(theme + ": id 0 0x{:08X} -> 0x{:08X}".format(UPSTREAM_ACCENT_BASES[theme], accent_base(theme)))


if __name__ == "__main__":
    main()
