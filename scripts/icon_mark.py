"""Rasterize ic_airplay.xml for portfolio / marketing assets."""
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw

VIEWPORT = 24.0
VP_TX = 3.05
VP_TY = 2.4
STROKE = 1.65
RADIUS = 2.2
PLATE = (28, 28, 28)
BACK_STROKE = (255, 255, 255, 153)
FRONT_STROKE = (255, 255, 255, 255)


def render_ic_airplay(size: int, plate_color: tuple[int, int, int] = PLATE) -> Image.Image:
    """PIL render — matches centered ic_airplay.xml geometry."""
    ss = 8
    canvas = int(VIEWPORT * ss)
    scale = ss
    half = STROKE / 2
    sw = STROKE * scale
    radius = RADIUS * scale

    def px(value: float) -> float:
        return (value + VP_TX) * scale

    def py(value: float) -> float:
        return (value + VP_TY) * scale

    img = Image.new("RGBA", (canvas, canvas), (*plate_color, 255))
    draw = ImageDraw.Draw(img)

    draw.rounded_rectangle(
        [px(0.6 - half), py(3.6 - half), px(14.2 + half), py(12.6 + half)],
        radius=radius,
        outline=BACK_STROKE,
        width=round(sw),
    )
    draw.rounded_rectangle(
        [px(3.2), py(6.2), px(17.6), py(16.2)],
        radius=radius,
        fill=(*plate_color, 255),
    )
    draw.rounded_rectangle(
        [px(3.6 - half), py(6.6 - half), px(17.2 + half), py(15.6 + half)],
        radius=radius,
        outline=FRONT_STROKE,
        width=round(sw),
    )

    return img.resize((size, size), Image.LANCZOS)


def load_ic_airplay_mark(
    size: int,
    plate_color: tuple[int, int, int] = PLATE,
    inset: float = 0.14,
) -> Image.Image:
    """Render centered mark with even margin inside the plate."""
    inner = max(1, int(size * (1 - inset * 2)))
    mark = render_ic_airplay(inner, plate_color)
    plate = Image.new("RGBA", (size, size), (*plate_color, 255))
    offset = (size - inner) // 2
    plate.paste(mark, (offset, offset), mark)
    return plate


if __name__ == "__main__":
    load_ic_airplay_mark(512).save("icon_mark_test.png")
    print("Saved icon_mark_test.png")
