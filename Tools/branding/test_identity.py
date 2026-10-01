import re
import unittest
from pathlib import Path

import brand


FORBIDDEN_IDENTIFIERS: list[str] = [
    'accountType="org.telegram.messenger"',
    "vnd.org.telegram.messenger",
    'targetPackage="org.telegram.messenger"',
    "org.telegram.messenger.OPEN_ACCOUNT",
    "org.telegram.messenger.CREATE_STICKER_PACK",
    "org.telegram.messenger.SHORTCUT_SHARE",
    "org.telegram.messenger.ACTION_",
    '"org.telegram.start"',
    '"org.telegram.account"',
    '"org.telegram.messenger"',
    '"org.telegram.messenger.beta"',
    "details?id=org.telegram.messenger",
    "package=org.telegram.messenger",
    "org.telegram.android.musicplayer",
]
TELEGRAM_HOSTS: list[str] = ["t.me", "telegram.me", "telegram.dog"]


def identity_sources() -> list[Path]:
    files: list[Path] = sorted(brand.JAVA.rglob("*.java"))
    files += sorted((brand.RES / "xml").glob("*.xml"))
    files.append(brand.MAIN / "AndroidManifest.xml")
    return files


class IdentityTest(unittest.TestCase):

    def test_package(self) -> None:
        self.assertIn("APP_PACKAGE=" + brand.PACKAGE, brand.read(brand.ROOT / "gradle.properties").splitlines())


    def test_app_name_in_every_locale(self) -> None:
        found: int = 0
        for strings in sorted(brand.RES.glob("values*/strings.xml")):
            text: str = brand.read(strings)
            for key, expected in (("AppName", brand.NAME), ("AppNameBeta", brand.NAME_BETA)):
                match: re.Match[str] | None = re.search(r'<string name="' + key + r'">([^<]*)</string>', text)
                if match is not None:
                    found += 1
                    self.assertEqual(expected, match.group(1), str(strings) + ": " + key)
        self.assertGreaterEqual(found, 11)


    def test_no_telegram_identifiers(self) -> None:
        hits: list[str] = []
        for path in identity_sources():
            text: str = brand.read(path)
            for identifier in FORBIDDEN_IDENTIFIERS:
                if identifier in text:
                    hits.append(path.relative_to(brand.ROOT).as_posix() + ": " + identifier)
        self.assertEqual([], hits)


    def test_brand_constants(self) -> None:
        build_vars: str = brand.read(brand.JAVA / "org" / "telegram" / "messenger" / "BuildVars.java")
        self.assertIn('public static final String BRAND_PACKAGE = "' + brand.PACKAGE + '";', build_vars)
        self.assertIn('public static final String BRAND_NAME = "' + brand.NAME + '";', build_vars)


    def test_deep_link_hosts(self) -> None:
        manifest: str = brand.read(brand.MAIN / "AndroidManifest.xml")
        for host in TELEGRAM_HOSTS:
            self.assertNotIn('android:host="' + host + '"', manifest)
        self.assertIn('android:host="' + brand.LINK_HOST + '" android:scheme="https"', manifest)
        self.assertIn('android:scheme="tg"', manifest)


    def test_firebase_plugin_removed(self) -> None:
        self.assertNotIn("com.google.gms.google-services", brand.read(brand.ROOT / "TMessagesProj_App" / "build.gradle"))
        self.assertFalse((brand.ROOT / "TMessagesProj_App" / "google-services.json").exists())
        self.assertNotIn("com.google.gms.google-services", brand.read(brand.ROOT / "TMessagesProj" / "build.gradle"))


if __name__ == "__main__":
    unittest.main()
