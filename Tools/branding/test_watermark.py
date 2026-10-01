import subprocess
import unittest
from io import BytesIO
from pathlib import Path

from PIL import Image

import brand


WATERMARK: Path = brand.RES / "raw" / "round_blur_overlay_text.png"
UPSTREAM_REF: str = brand.UPSTREAM_COMMIT + ":TMessagesProj/src/main/res/raw/round_blur_overlay_text.png"
MIN_OPAQUE_PIXELS: int = 1000


class WatermarkTest(unittest.TestCase):
    def upstream_bytes(self) -> bytes:
        return subprocess.run(
            ["git", "-C", str(brand.ROOT), "show", UPSTREAM_REF],
            check=True,
            capture_output=True,
        ).stdout

    def test_same_size_as_upstream(self) -> None:
        upstream: Image.Image = Image.open(BytesIO(self.upstream_bytes()))
        current: Image.Image = Image.open(WATERMARK)
        self.assertEqual(upstream.size, current.size)
        self.assertEqual(upstream.mode, current.mode)

    def test_differs_from_upstream(self) -> None:
        self.assertFalse(self.upstream_bytes() == WATERMARK.read_bytes(), "watermark is still the upstream blob")

    def test_not_blank(self) -> None:
        alpha: Image.Image = Image.open(WATERMARK).convert("RGBA").getchannel("A")
        opaque: int = sum(1 for value in alpha.tobytes() if value > 200)
        self.assertGreater(opaque, MIN_OPAQUE_PIXELS)


if __name__ == "__main__":
    unittest.main()
