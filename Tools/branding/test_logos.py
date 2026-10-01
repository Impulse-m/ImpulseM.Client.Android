import unittest

from PIL import Image

import brand


PLANE_SIZES: dict[str, int] = {
    "mdpi": 150,
    "hdpi": 225,
    "xhdpi": 300,
    "xxhdpi": 450,
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
            self.assertEqual((size, size), image.size, density)
            opaque: Image.Image = image.getchannel("A").point(lambda value: 255 if value > 128 else 0)
            box: tuple[int, int, int, int] | None = opaque.getbbox()
            self.assertIsNotNone(box, density)
            left, _, right, _ = box
            self.assertAlmostEqual(size / 2, (left + right) / 2, delta=2, msg=density + " horizontal centre")
            self.assertAlmostEqual(size * (22 - 7) / 200, left, delta=2, msg=density + " left edge")
            self.assertAlmostEqual(size * (178 + 7) / 200, right, delta=2, msg=density + " right edge")
            baseline: tuple[int, ...] = image.getpixel((round(size * 40 / 200), size // 2))
            self.assertGreater(baseline[3], 128, density + " pulse baseline must be opaque")


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
