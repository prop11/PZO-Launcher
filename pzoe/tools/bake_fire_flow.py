"""Bake looping flame and smoke flow fields for PZOE's screen shader."""
import base64
import gzip
import math
from pathlib import Path

SIZE = 48
FRAMES = 12
TAU = math.tau
MAX_MOTION = 0.125


def waves(x, y, modes):
    return sum(amplitude * math.sin(TAU * (u * x + v * y) + phase)
               for u, v, amplitude, phase in modes)


def warp(x, y, phase):
    dx = 0.060 * math.sin(TAU * (y + x) - phase) + 0.035 * math.sin(TAU * y + phase)
    dy = 0.055 * math.sin(TAU * (2 * x - y) + phase) + 0.028 * math.cos(TAU * x - phase)
    return x + dx, y + dy


def forward(x, y, phase, target_x, target_y):
    # Invert the next frame's domain warp to follow the same piece of smoke.
    next_phase = phase + TAU / FRAMES
    nx, ny = x, y
    for _ in range(10):
        wx, wy = warp(nx, ny, next_phase)
        nx += 0.8 * (target_x - wx)
        ny += 0.8 * (target_y - wy)
    return nx - x, ny - y


def bake():
    smoke_modes = ((2, 1, 0.24, 0.7), (-1, 3, 0.18, 2.2), (4, 2, 0.12, 2.8),
                   (3, -5, 0.10, 1.3), (8, 3, 0.058, 0.9), (-6, 7, 0.048, 0.1))
    flame_modes = ((2, 2, 0.24, 1.4), (-2, 3, 0.16, 0.8), (5, -2, 0.11, 4.0),
                   (3, 5, 0.075, 2.8), (7, 5, 0.055, 0.1))
    output = bytearray()
    for frame in range(FRAMES):
        phase = TAU * frame / FRAMES
        for iy in range(SIZE):
            y = (iy + 0.5) / SIZE
            for ix in range(SIZE):
                x = (ix + 0.5) / SIZE
                wx, wy = warp(x, y, phase)
                smoke = 0.50 + waves(wx, wy, smoke_modes)
                flame = 0.50 + waves(wx, wy, flame_modes)
                mx, my = forward(x, y, phase, wx, wy)
                output.extend(round(255 * max(0, min(1, value))) for value in (smoke, flame))
                output.extend(128 + round(127 * max(-1, min(1, value / MAX_MOTION))) for value in (mx, my))
    return output


if __name__ == "__main__":
    resources = Path(__file__).resolve().parents[1] / "resources/com/pzoptimizer/pzoe"
    resources.mkdir(parents=True, exist_ok=True)
    payload = base64.encodebytes(gzip.compress(bake(), mtime=0))
    (resources / "fire-flow.rgba8.gz.b64").write_bytes(payload)
