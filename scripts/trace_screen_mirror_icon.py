#!/usr/bin/env python3
"""Build Screen Mirroring icon from Apple SF Symbol geometry (rectangle.on.rectangle)."""
from __future__ import annotations

import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np

OUT_XML = Path(__file__).resolve().parent.parent / "app/src/main/res/drawable/ic_airplay.xml"
VIEWPORT = 24.0
SYMBOL_NAME = "rectangle.on.rectangle"


@dataclass
class RoundRect:
    x: float
    y: float
    w: float
    h: float
    r: float

    def stroke_path(self) -> str:
        r = min(self.r, self.w / 2, self.h / 2)
        x2, y2 = self.x + self.w, self.y + self.h
        return (
            f"M{self.x + r:.3f},{self.y:.3f} H{x2 - r:.3f} "
            f"A{r:.3f},{r:.3f} 0 0,1 {x2:.3f},{self.y + r:.3f} "
            f"V{y2 - r:.3f} A{r:.3f},{r:.3f} 0 0,1 {x2 - r:.3f},{y2:.3f} "
            f"H{self.x + r:.3f} A{r:.3f},{r:.3f} 0 0,1 {self.x:.3f},{y2 - r:.3f} "
            f"V{self.y + r:.3f} A{r:.3f},{r:.3f} 0 0,1 {self.x + r:.3f},{self.y:.3f} Z"
        )


def export_sf_symbol_png() -> Path:
    tmp = Path(tempfile.gettempdir()) / "sf_rectangle_on_rectangle.png"
    script = f"""
import AppKit
let size: CGFloat = 512
let base = NSImage(systemSymbolName: "{SYMBOL_NAME}", accessibilityDescription: nil)!
let config = NSImage.SymbolConfiguration(pointSize: size, weight: .regular)
let img = base.withSymbolConfiguration(config)!
let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(size), pixelsHigh: Int(size),
    bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
    colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
NSColor.clear.set()
NSRect(x: 0, y: 0, width: size, height: size).fill()
NSColor.black.set()
img.draw(in: NSRect(x: 0, y: 0, width: size, height: size))
NSGraphicsContext.restoreGraphicsState()
let data = rep.representation(using: .png, properties: [:])!
try! data.write(to: URL(fileURLWithPath: "{tmp}"))
"""
    with tempfile.NamedTemporaryFile("w", suffix=".swift", delete=False) as handle:
        handle.write(script)
        swift_file = handle.name
    subprocess.run(["swift", swift_file], check=True)
    return tmp


def measure_from_sf_png(png_path: Path) -> tuple[RoundRect, RoundRect, float]:
    img = cv2.imread(str(png_path), cv2.IMREAD_UNCHANGED)
    if img is None:
        raise ValueError(f"Missing {png_path}")

    if img.shape[2] == 4:
        mask = (img[:, :, 3] > 0) & (img[:, :, :3].max(axis=2) < 200)
        mask = mask.astype(np.uint8) * 255
    else:
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        _, mask = cv2.threshold(gray, 100, 255, cv2.THRESH_BINARY_INV)

    coords = cv2.findNonZero(mask)
    if coords is None:
        raise ValueError("No symbol pixels found")

    ox, oy, fw, fh = cv2.boundingRect(coords)
    crop = mask[oy : oy + fh, ox : ox + fw]

    dist = cv2.distanceTransform(crop, cv2.DIST_L2, 5)
    samples = dist[crop > 0]
    if samples.size == 0:
        raise ValueError("Empty distance transform")
    stroke_px = float(np.percentile(samples, 85) * 2)
    inset = stroke_px / 2

    ch, cw = crop.shape
    back_seed = np.zeros_like(crop)
    front_seed = np.zeros_like(crop)
    back_seed[: ch // 2 + 20, : cw // 2 + 20] = crop[: ch // 2 + 20, : cw // 2 + 20]
    front_seed[ch // 2 - 20 :, cw // 2 - 20 :] = crop[ch // 2 - 20 :, cw // 2 - 20 :]

    kernel = np.ones((5, 5), np.uint8)
    rects: list[RoundRect] = []
    for seed in (back_seed, front_seed):
        closed = cv2.morphologyEx(seed, cv2.MORPH_CLOSE, kernel, iterations=2)
        coords = cv2.findNonZero(closed)
        if coords is None:
            raise ValueError("Could not isolate rectangle stroke")
        bx, by, bw, bh = cv2.boundingRect(coords)
        rects.append(
            RoundRect(
                bx + inset,
                by + inset,
                bw - stroke_px,
                bh - stroke_px,
                min(bw, bh) * 0.22,
            )
        )

    scale = (VIEWPORT - 4) / max(fw, fh)
    pad = (VIEWPORT - fw * scale) / 2

    def map_rect(rect: RoundRect) -> RoundRect:
        return RoundRect(
            rect.x * scale + pad,
            rect.y * scale + pad,
            rect.w * scale,
            rect.h * scale,
            rect.r * scale,
        )

    back, front = map_rect(rects[0]), map_rect(rects[1])
    avg_w = (back.w + front.w) / 2
    avg_h = (back.h + front.h) / 2
    avg_r = (back.r + front.r) / 2
    back.w = front.w = avg_w
    back.h = front.h = avg_h
    back.r = front.r = avg_r
    return back, front, stroke_px * scale


def write_xml(back: RoundRect, front: RoundRect, stroke: float) -> None:
    OUT_XML.write_text(
        f"""<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="64dp"
    android:height="64dp"
    android:viewportWidth="{VIEWPORT:.0f}"
    android:viewportHeight="{VIEWPORT:.0f}">

    <!-- Apple SF Symbol: {SYMBOL_NAME} (Screen Mirroring) -->
    <path
        android:fillColor="@android:color/transparent"
        android:pathData="{back.stroke_path()}"
        android:strokeColor="@color/text_primary"
        android:strokeWidth="{stroke:.2f}"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />

    <path
        android:fillColor="@android:color/transparent"
        android:pathData="{front.stroke_path()}"
        android:strokeColor="@color/text_primary"
        android:strokeWidth="{stroke:.2f}"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
</vector>
""",
        encoding="utf-8",
    )


def main() -> None:
    png = export_sf_symbol_png()
    back, front, stroke = measure_from_sf_png(png)
    if back.w <= 0 or front.w <= 0 or stroke <= 0 or stroke > 6:
        raise SystemExit(f"Bad geometry: back={back}, front={front}, stroke={stroke}")
    write_xml(back, front, stroke)
    print(f"Wrote {OUT_XML}")
    print(f"back={back}")
    print(f"front={front}")
    print(f"stroke={stroke:.2f}")


if __name__ == "__main__":
    main()
