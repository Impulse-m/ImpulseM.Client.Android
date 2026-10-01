import unittest

from PIL import Image

import brand


LAUNCHER_SIZES: dict[str, int] = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}
REMOVED_ALIASES: list[str] = ["VintageIcon", "AquaIcon", "PremiumIcon", "TurboIcon", "NoxIcon"]


def near(actual: tuple[int, ...], expected: int, tolerance: int) -> bool:
    channels: tuple[int, int, int] = ((expected >> 16) & 0xFF, (expected >> 8) & 0xFF, expected & 0xFF)
    return all(abs(actual[i] - channels[i]) <= tolerance for i in range(3))


class IconsTest(unittest.TestCase):

    def test_legacy_launcher_pngs(self) -> None:
        for density, size in LAUNCHER_SIZES.items():
            for name in ("ic_launcher.png", "ic_launcher_round.png"):
                image: Image.Image = Image.open(brand.RES / ("mipmap-" + density) / name).convert("RGBA")
                self.assertEqual((size, size), image.size, density + "/" + name)
                disc_point: tuple[int, ...] = image.getpixel((round(size * 0.3), round(size * 0.3)))
                self.assertTrue(near(disc_point, brand.PRIMARY_DAY, 12), density + " disc " + str(disc_point))
                on_line: tuple[int, ...] = image.getpixel((round(size * 40 / 200), size // 2))
                self.assertTrue(near(on_line, brand.WHITE, 40), density + " pulse " + str(on_line))


    def test_account_icon_pngs(self) -> None:
        for density, size in LAUNCHER_SIZES.items():
            folder = brand.RES / ("drawable-" + density)
            self.assertFalse((folder / "ic_launcher_dr.webp").exists(), density)
            self.assertEqual((size, size), Image.open(folder / "ic_launcher_dr.png").size, density)


    def test_adaptive_icons(self) -> None:
        for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
            text: str = brand.read(brand.RES / "mipmap-anydpi-v26" / name)
            self.assertIn('@drawable/ic_launcher_background_impulsem', text)
            self.assertIn('<foreground android:drawable="@drawable/ic_launcher_foreground_impulsem"', text)
            self.assertIn('<monochrome android:drawable="@drawable/ic_launcher_foreground_impulsem"', text)
            self.assertNotIn("icon_plane", text)
        foreground: str = brand.read(brand.RES / "drawable" / "ic_launcher_foreground_impulsem.xml")
        self.assertIn(brand.pulse_path_data(), foreground)


    def test_notification_icon(self) -> None:
        self.assertEqual([], sorted(brand.RES.glob("drawable-*/notification.webp")))
        self.assertIn(brand.pulse_path_data(), brand.read(brand.RES / "drawable" / "notification.xml"))


    def test_splash(self) -> None:
        for folder in ("values-v31", "values-night"):
            styles: str = brand.read(brand.RES / folder / "styles.xml")
            self.assertNotIn("tg_splash_320", styles, folder)
        self.assertIn("@drawable/splash_impulsem", brand.read(brand.RES / "values-v31" / "styles.xml"))


    def test_alternate_icons_removed(self) -> None:
        manifest: str = brand.read(brand.MAIN / "AndroidManifest.xml")
        for alias in REMOVED_ALIASES:
            self.assertNotIn(alias, manifest)
        self.assertIn("DefaultIcon", manifest)
        controller: str = brand.read(brand.JAVA / "org" / "telegram" / "ui" / "LauncherIconController.java")
        for name in ("VINTAGE", "AQUA", "PREMIUM", "TURBO", "NOX"):
            self.assertNotIn(name + "(", controller)


if __name__ == "__main__":
    unittest.main()
