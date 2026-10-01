"""Single source of truth for ImpulseM brand values used by the rebranding tools and checks."""

import subprocess
from pathlib import Path


ROOT: Path = Path(__file__).resolve().parents[2]
MAIN: Path = ROOT / "TMessagesProj" / "src" / "main"
RES: Path = MAIN / "res"
JAVA: Path = MAIN / "java"
ASSETS: Path = MAIN / "assets"

NAME: str = "ImpulseM"
NAME_BETA: str = "ImpulseM Beta"
PACKAGE: str = "net.impulsem.messenger"
LINK_HOST: str = "o.impulsem.net"

PRIMARY_DAY: int = 0xFF2085DF
LINK_DAY: int = 0xFF106ACC
PRIMARY_NIGHT: int = 0xFF58AEEE
NIGHT_SURFACE: int = 0xFF1D2128
NIGHT_BACKGROUND: int = 0xFF14181F
NIGHT_DIVIDER: int = 0xFF0B0F15
WHITE: int = 0xFFFFFFFF

UPSTREAM_COMMIT: str = "f2908b1"

LOGO_VIEWBOX: int = 200
PULSE_POINTS: list[tuple[int, int]] = [
    (22, 100),
    (64, 100),
    (74, 100),
    (84, 70),
    (98, 146),
    (112, 44),
    (126, 100),
    (136, 100),
    (178, 100),
]
LOGO_STROKE: int = 14
GLYPH_STROKE: int = 16


def argb_to_attheme(color: int) -> int:
    """Converts an unsigned 0xAARRGGBB value to the signed decimal form stored in .attheme files."""
    return color - (1 << 32) if color >= (1 << 31) else color


def attheme_to_argb(value: int) -> int:
    return value & 0xFFFFFFFF


def hex_rgb(color: int) -> str:
    return "#{:06X}".format(color & 0xFFFFFF)


def pulse_path_data() -> str:
    first: tuple[int, int] = PULSE_POINTS[0]
    rest: list[tuple[int, int]] = PULSE_POINTS[1:]
    return "M{},{} ".format(first[0], first[1]) + " ".join("L{},{}".format(x, y) for x, y in rest)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def upstream(path: Path) -> str:
    """Returns the file content at the upstream Telegram commit, with LF line endings."""
    relative: str = path.resolve().relative_to(ROOT).as_posix()
    completed: subprocess.CompletedProcess[bytes] = subprocess.run(
        ["git", "-C", str(ROOT), "show", UPSTREAM_COMMIT + ":" + relative],
        check=True,
        capture_output=True,
    )
    return completed.stdout.decode("utf-8").replace("\r\n", "\n")
