"""Generates every ImpulseM logo asset from brand.py. Re-running reproduces identical files."""

from pathlib import Path

from PIL import Image, ImageDraw

import brand


SUPERSAMPLE: int = 8
LAUNCHER_SIZES: dict[str, int] = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}
LEGACY_INSET: float = 0.04
ADAPTIVE_SCALE: float = 0.36
ADAPTIVE_OFFSET: float = 18.0
SPLASH_SIZE: int = 288
SPLASH_DISC: int = 192
VECTOR_HEADER: str = '<?xml version="1.0" encoding="utf-8"?>\n'


def rgba(color: int) -> tuple[int, int, int, int]:
    return ((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, (color >> 24) & 0xFF)


def draw_pulse(
    draw: ImageDraw.ImageDraw,
    scale: float,
    offset_x: float,
    offset_y: float,
    stroke: float,
    color: tuple[int, int, int, int],
) -> None:
    points: list[tuple[float, float]] = [(offset_x + x * scale, offset_y + y * scale) for x, y in brand.PULSE_POINTS]
    width: int = max(1, round(stroke * scale))
    draw.line(points, fill=color, width=width, joint="curve")
    radius: float = width / 2
    for x, y in (points[0], points[-1]):
        draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill=color)


def render_logo(size: int, inset: float) -> Image.Image:
    big: int = size * SUPERSAMPLE
    image: Image.Image = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw: ImageDraw.ImageDraw = ImageDraw.Draw(image)
    margin: float = big * inset
    diameter: float = big - 2 * margin
    draw.ellipse((margin, margin, margin + diameter, margin + diameter), fill=rgba(brand.PRIMARY_DAY))
    draw_pulse(draw, diameter / brand.LOGO_VIEWBOX, margin, margin, brand.LOGO_STROKE, rgba(brand.WHITE))
    return image.resize((size, size), Image.LANCZOS)


def render_glyph(width: int, height: int, color: int, fill_ratio: float) -> Image.Image:
    """Draws the pulse alone, scaled so its stroked bounding box fills fill_ratio of the canvas, centered."""
    xs: list[int] = [x for x, _ in brand.PULSE_POINTS]
    ys: list[int] = [y for _, y in brand.PULSE_POINTS]
    box_width: float = max(xs) - min(xs) + brand.GLYPH_STROKE
    box_height: float = max(ys) - min(ys) + brand.GLYPH_STROKE
    big_width: int = width * SUPERSAMPLE
    big_height: int = height * SUPERSAMPLE
    scale: float = min(big_width * fill_ratio / box_width, big_height * fill_ratio / box_height)
    center_x: float = (max(xs) + min(xs)) / 2
    center_y: float = (max(ys) + min(ys)) / 2
    image: Image.Image = Image.new("RGBA", (big_width, big_height), (0, 0, 0, 0))
    draw: ImageDraw.ImageDraw = ImageDraw.Draw(image)
    draw_pulse(draw, scale, big_width / 2 - center_x * scale, big_height / 2 - center_y * scale, brand.GLYPH_STROKE, rgba(color))
    return image.resize((width, height), Image.LANCZOS)


def pulse_vector_path(color: str, stroke: int) -> str:
    return (
        '        <path\n'
        '            android:pathData="' + brand.pulse_path_data() + '"\n'
        '            android:fillColor="#00000000"\n'
        '            android:strokeColor="' + color + '"\n'
        '            android:strokeWidth="' + str(stroke) + '"\n'
        '            android:strokeLineCap="round"\n'
        '            android:strokeLineJoin="round" />\n'
    )


def vector(size_dp: int, viewport: int, body: str) -> str:
    return (
        VECTOR_HEADER
        + '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        + '    android:width="' + str(size_dp) + 'dp"\n'
        + '    android:height="' + str(size_dp) + 'dp"\n'
        + '    android:viewportWidth="' + str(viewport) + '"\n'
        + '    android:viewportHeight="' + str(viewport) + '">\n'
        + body
        + '</vector>\n'
    )


def group(scale: float, offset: float, body: str) -> str:
    return (
        '    <group\n'
        '        android:scaleX="' + str(scale) + '"\n'
        '        android:scaleY="' + str(scale) + '"\n'
        '        android:translateX="' + str(offset) + '"\n'
        '        android:translateY="' + str(offset) + '">\n'
        + body
        + '    </group>\n'
    )


def disc(color: str) -> str:
    return '        <path android:pathData="M100,0 A100,100 0 1,1 100,200 A100,100 0 1,1 100,0 Z" android:fillColor="' + color + '" />\n'


def write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8", newline="\n")


def write_launcher_pngs() -> None:
    for density, size in LAUNCHER_SIZES.items():
        image: Image.Image = render_logo(size, LEGACY_INSET)
        for name in ("ic_launcher.png", "ic_launcher_round.png"):
            image.save(brand.RES / ("mipmap-" + density) / name, optimize=True)
        account: Path = brand.RES / ("drawable-" + density) / "ic_launcher_dr.webp"
        if account.exists():
            account.unlink()
        image.save(brand.RES / ("drawable-" + density) / "ic_launcher_dr.png", optimize=True)


def write_vectors() -> None:
    primary: str = brand.hex_rgb(brand.PRIMARY_DAY)
    white: str = brand.hex_rgb(brand.WHITE)
    drawable: Path = brand.RES / "drawable"
    write(drawable / "ic_impulsem_logo.xml", vector(200, 200, disc(primary) + pulse_vector_path(white, brand.LOGO_STROKE)))
    write(
        drawable / "ic_launcher_background_impulsem.xml",
        VECTOR_HEADER
        + '<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">\n'
        + '    <solid android:color="' + primary + '" />\n'
        + '</shape>\n',
    )
    write(
        drawable / "ic_launcher_foreground_impulsem.xml",
        vector(108, 108, group(ADAPTIVE_SCALE, ADAPTIVE_OFFSET, pulse_vector_path(white, brand.LOGO_STROKE))),
    )
    write(drawable / "notification.xml", vector(24, 200, pulse_vector_path(white, brand.GLYPH_STROKE)))
    splash_scale: float = SPLASH_DISC / brand.LOGO_VIEWBOX
    splash_offset: float = (SPLASH_SIZE - SPLASH_DISC) / 2
    write(
        drawable / "splash_impulsem.xml",
        vector(SPLASH_SIZE, SPLASH_SIZE, group(splash_scale, splash_offset, disc(primary) + pulse_vector_path(white, brand.LOGO_STROKE))),
    )
    for webp in sorted(brand.RES.glob("drawable-*/notification.webp")):
        webp.unlink()


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
PLANE_FILL: float = 0.92


def write_logo_rasters() -> None:
    for density, (width, height) in PLANE_SIZES.items():
        folder: Path = brand.RES / ("drawable-" + density)
        old: Path = folder / "intro_tg_plane.webp"
        if old.exists():
            old.unlink()
        render_glyph(width, height, brand.WHITE, PLANE_FILL).save(folder / "intro_tg_plane.png", optimize=True)
    for density, size in LOGO_MIDDLE_SIZES.items():
        folder = brand.RES / ("drawable-" + density)
        old = folder / "logo_middle.webp"
        if old.exists():
            old.unlink()
        render_logo(size, 0.0).save(folder / "logo_middle.png", optimize=True)
    write(brand.RES / "drawable" / "menu_invit_telegram.xml", vector(24, 200, pulse_vector_path("#FFFFFFFF", brand.GLYPH_STROKE)))
    for webp in sorted(brand.RES.glob("drawable-*/menu_invit_telegram.webp")):
        webp.unlink()


def main() -> None:
    write_launcher_pngs()
    write_vectors()
    write_logo_rasters()


if __name__ == "__main__":
    main()
