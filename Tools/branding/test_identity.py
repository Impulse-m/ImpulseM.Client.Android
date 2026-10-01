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


    def test_app_name_comes_from_one_variable(self) -> None:
        self.assertIn("IMPULSEM_APP_NAME=" + brand.NAME, brand.read(brand.ROOT / "gradle.properties").splitlines())
        gradle: str = brand.read(brand.ROOT / "TMessagesProj" / "build.gradle")
        self.assertRegex(gradle, r'resValue\s+"string",\s*"AppName"')
        self.assertRegex(gradle, r'resValue\s+"string",\s*"AppNameBeta"')
        self.assertRegex(gradle, r'buildConfigField\s+"String",\s*"IMPULSEM_APP_NAME"')
        defining: list[str] = []
        for strings in sorted(brand.RES.glob("values*/strings.xml")):
            if re.search(r'<string\s+name="AppName(Beta)?"', brand.read(strings)):
                defining.append(strings.relative_to(brand.ROOT).as_posix())
        self.assertEqual([], defining)


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
        self.assertIn("public static final String BRAND_NAME = BuildConfig.IMPULSEM_APP_NAME;", build_vars)
        self.assertNotIn('BRAND_NAME = "', build_vars)


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
