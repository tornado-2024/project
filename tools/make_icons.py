#!/usr/bin/env python3
"""
Генерирует набор PNG-иконок приложения (варианты для activity-alias).

Использование:
    python3 tools/make_icons.py            # все варианты в res/mipmap-*
    python3 tools/make_icons.py blue       # только один вариант
"""
import os
import struct
import sys
import zlib

# id -> (цвет, стиль)
VARIANTS = {
    'default': ((0x4C, 0x8D, 0xFF), 'circle'),
    'light':   ((0xF2, 0xF4, 0xF8), 'rounded'),
    'dark':    ((0x14, 0x1A, 0x24), 'circle'),
    'beta':    ((0xFF, 0x8A, 0x3D), 'rounded'),
}

DENSITIES = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}


def png(width, height, pixels):
    raw = b''
    for row in pixels:
        raw += b'\x00' + b''.join(bytes(p) for p in row)

    def chunk(tag, data):
        return (struct.pack('>I', len(data)) + tag + data
                + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff))

    return (b'\x89PNG\r\n\x1a\n'
            + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9))
            + chunk(b'IEND', b''))


def render(size, color, style):
    """Рисуем иконку: круг или скруглённый квадрат с точкой-центром."""
    bg = (0x14, 0x1A, 0x24, 255)
    fg = (color[0], color[1], color[2], 255)
    accent = (0xFF, 0xB7, 0x4D, 255)
    c = size / 2.0
    r = size * 0.40
    rows = []
    for y in range(size):
        row = []
        for x in range(size):
            dx, dy = x + 0.5 - c, y + 0.5 - c
            inside = False
            if style == 'rounded':
                # скруглённый квадрат
                m = size * 0.12
                ax = abs(dx) - (size * 0.44 - m)
                ay = abs(dy) - (size * 0.44 - m)
                if abs(dx) <= size * 0.44 and abs(dy) <= size * 0.44:
                    if (ax <= 0 or ay <= 0 or (ax * ax + ay * ay) <= m * m):
                        inside = True
            else:
                if dx * dx + dy * dy <= r * r:
                    inside = True
            if not inside:
                row.append(bg)
                continue
            # «глаз» в центре
            d = (dx * dx + dy * dy) ** 0.5
            if d <= size * 0.13:
                row.append(accent)
            else:
                row.append(fg)
        rows.append(row)
    return png(size, size, rows)


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    res = os.path.join(root, 'app', 'src', 'main', 'res')
    only = sys.argv[1] if len(sys.argv) > 1 else None

    for vid, (color, style) in VARIANTS.items():
        if only and vid != only:
            continue
        for dens, size in DENSITIES.items():
            out_dir = os.path.join(res, 'mipmap-' + dens)
            os.makedirs(out_dir, exist_ok=True)
            name = 'ic_launcher' if vid == 'default' else 'ic_launcher_' + vid
            path = os.path.join(out_dir, name + '.png')
            with open(path, 'wb') as f:
                f.write(render(size, color, style))
        print('  %-8s -> mipmap-*/%s.png' % (vid, 'ic_launcher' if vid == 'default' else 'ic_launcher_' + vid))


if __name__ == '__main__':
    main()
