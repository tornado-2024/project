#!/usr/bin/env python3
"""
Генерирует простой PNG (иконку) без внешних библиотек.
Нужен, чтобы проверить, не мешает ли установщику векторная иконка.

Использование: python3 make_icon.py out.png 192
"""
import struct
import sys
import zlib


def png(width, height, pixels):
    """pixels: список строк, каждая — список (r, g, b, a)."""
    raw = b''
    for row in pixels:
        raw += b'\x00' + b''.join(bytes(p) for p in row)

    def chunk(tag, data):
        c = struct.pack('>I', len(data)) + tag + data
        return c + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)

    return (b'\x89PNG\r\n\x1a\n'
            + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9))
            + chunk(b'IEND', b''))


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else 'icon.png'
    size = int(sys.argv[2]) if len(sys.argv) > 2 else 192
    bg = (0x14, 0x1A, 0x24, 255)
    fg = (0x4C, 0x8D, 0xFF, 255)
    cx = cy = size / 2.0
    r_out = size * 0.40
    r_in = size * 0.20
    rows = []
    for y in range(size):
        row = []
        for x in range(size):
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            d = (dx * dx + dy * dy) ** 0.5
            if d <= r_in:
                row.append(fg)
            elif d <= r_out:
                row.append(fg)
            else:
                row.append(bg)
        rows.append(row)
    with open(out, 'wb') as f:
        f.write(png(size, size, rows))
    print('  ' + out + ' (' + str(size) + 'x' + str(size) + ')')


if __name__ == '__main__':
    main()
