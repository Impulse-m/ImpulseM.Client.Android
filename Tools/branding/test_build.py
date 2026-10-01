import re
import unittest

import brand


DEBUG_ABIS_LINE: str = "IMPULSEM_DEBUG_ABIS=arm64-v8a,x86_64"


def build_type_block(gradle: str, name: str) -> str:
    match: re.Match[str] | None = re.search(r"\n        " + name + r" \{\n(.*?)\n        \}\n", gradle, re.S)
    if match is None:
        raise AssertionError("build type " + name + " not found")
    return match.group(1)


class BuildTest(unittest.TestCase):

    def test_debug_abis_property(self) -> None:
        self.assertIn(DEBUG_ABIS_LINE, brand.read(brand.ROOT / "gradle.properties").splitlines())


    def test_library_debug_uses_property(self) -> None:
        gradle: str = brand.read(brand.ROOT / "TMessagesProj" / "build.gradle")
        self.assertIn("IMPULSEM_DEBUG_ABIS", build_type_block(gradle, "debug"))


    def test_release_has_no_abi_restriction(self) -> None:
        gradle: str = brand.read(brand.ROOT / "TMessagesProj" / "build.gradle")
        self.assertNotIn("abiFilters", build_type_block(gradle, "release"))


    def test_branding_out_is_ignored(self) -> None:
        self.assertIn("branding-out/", brand.read(brand.ROOT / ".gitignore").splitlines())


if __name__ == "__main__":
    unittest.main()
