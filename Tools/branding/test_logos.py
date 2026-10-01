import unittest

from PIL import Image

import brand


PLANE_SIZES: dict[str, tuple[int, int]] = {
    "mdpi": (82, 74),
    "hdpi": (123, 111),
    "xhdpi": (164, 148),
    "xxhdpi": (246, 222),
}
LOGO_MIDDLE_SIZES: dict[str, int] = {
    "mdpi": 68,
    "hdpi": 102,
    "xhdpi": 136,
    "xxhdpi": 204,
}


class LogosTest(unittest.TestCase):

    def test_intro_texture_is_pulse(self) -> None:
        for density, size in PLANE_SIZES.items():
            folder = brand.RES / ("drawable-" + density)
            self.assertFalse((folder / "intro_tg_plane.webp").exists(), density)
            image: Image.Image = Image.open(folder / "intro_tg_plane.png").convert("RGBA")
            self.assertEqual(size, image.size, density)
            left_middle: tuple[int, ...] = image.getpixel((round(size[0] * 0.12), size[1] // 2))
            self.assertGreater(left_middle[3], 128, density + " pulse baseline must be opaque")


    def test_logo_middle(self) -> None:
        for density, size in LOGO_MIDDLE_SIZES.items():
            folder = brand.RES / ("drawable-" + density)
            self.assertFalse((folder / "logo_middle.webp").exists(), density)
            self.assertEqual((size, size), Image.open(folder / "logo_middle.png").size, density)


    def test_invite_icon(self) -> None:
        self.assertEqual([], sorted(brand.RES.glob("drawable-*/menu_invit_telegram.webp")))
        self.assertIn(brand.pulse_path_data(), brand.read(brand.RES / "drawable" / "menu_invit_telegram.xml"))


    def test_wordmarks_gone(self) -> None:
        offenders: list[str] = []
        for path in sorted(brand.JAVA.rglob("*.java")):
            text: str = brand.read(path)
            for name in ("R.drawable.telegram_logo", "R.raw.qr_code_logo", "R.raw.plane_logo_plain"):
                if name in text:
                    offenders.append(path.name + ": " + name)
        self.assertEqual([], offenders)
        self.assertFalse((brand.RES / "drawable" / "telegram_logo.xml").exists())
        self.assertFalse((brand.RES / "drawable" / "telegram_logo_2.xml").exists())


    def test_qr_logo_svg(self) -> None:
        svg: str = brand.read(brand.RES / "raw" / "qr_logo.svg")
        self.assertIn("M22 100", svg)


if __name__ == "__main__":
    unittest.main()
