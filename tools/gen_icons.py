#!/usr/bin/env python3
"""生成 launcher PNG 图标（老 ROM/老 launcher 对矢量图标支持不佳）。

设计与 res/drawable/ic_launcher_ota.xml 一致（108x108 视图坐标）：
  - 圆角方块底 #1B6C3A
  - 白色下载箭头 #FFFFFF（杆 50..58 / 26..52，箭头三角 (44,52)-(64,52)-(54,66)）
  - 浅绿底座条 #A6F5B8（x 30..78，y 72..80）
输出 mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png
"""
import os, struct, zlib

BG = (0x1B, 0x6C, 0x3A)
FG = (0xFF, 0xFF, 0xFF)
BASE = (0xA6, 0xF5, 0xB8)
SS = 4  # 超采样倍数


def in_round_rect(x, y, x0, y0, x1, y1, r):
    if not (x0 <= x <= x1 and y0 <= y <= y1):
        return False
    cx = min(max(x, x0 + r), x1 - r)
    cy = min(max(y, y0 + r), y1 - r)
    dx, dy = x - cx, y - cy
    return dx * dx + dy * dy <= r * r


def in_triangle(px, py, a, b, c):
    def sign(p1, p2, p3):
        return (p1[0] - p3[0]) * (p2[1] - p3[1]) - (p2[0] - p3[0]) * (p1[1] - p3[1])

    d1, d2, d3 = sign((px, py), a, b), sign((px, py), b, c), sign((px, py), c, a)
    neg = (d1 < 0) or (d2 < 0) or (d3 < 0)
    pos = (d1 > 0) or (d2 > 0) or (d3 > 0)
    return not (neg and pos)


def sample(x, y):
    """返回 (r,g,b,a)；x,y 为 108 视图坐标"""
    if not in_round_rect(x, y, 0, 0, 108, 108, 22):
        return (0, 0, 0, 0)
    if 50 <= x <= 58 and 26 <= y <= 52:                      # 箭杆
        return FG + (255,)
    if in_triangle(x, y, (44, 52), (64, 52), (54, 66)):      # 箭头
        return FG + (255,)
    if 30 <= x <= 78 and 72 <= y <= 80:                      # 底座
        return BASE + (255,)
    return BG + (255,)


def render(size):
    rows = []
    step = 108.0 / (size * SS)
    for py in range(size):
        row = []
        for px in range(size):
            acc = [0.0, 0.0, 0.0, 0.0]
            for sy in range(SS):
                for sx in range(SS):
                    x = (px * SS + sx + 0.5) * step
                    y = (py * SS + sy + 0.5) * step
                    r, g, b, a = sample(x, y)
                    acc[0] += r * a
                    acc[1] += g * a
                    acc[2] += b * a
                    acc[3] += a
            n = SS * SS
            alpha = acc[3] / n
            if alpha <= 0:
                row.append((0, 0, 0, 0))
            else:
                row.append((int(acc[0] / acc[3] + 0.5), int(acc[1] / acc[3] + 0.5),
                            int(acc[2] / acc[3] + 0.5), int(alpha + 0.5)))
        rows.append(row)
    return rows


def write_png(path, rows, size):
    raw = b"".join(b"\x00" + b"".join(bytes(px) for px in row) for row in rows)

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(raw, 9))
           + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(png)


def main():
    root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")
    for folder, size in [("mipmap-mdpi", 48), ("mipmap-hdpi", 72), ("mipmap-xhdpi", 96),
                         ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192)]:
        d = os.path.join(root, folder)
        os.makedirs(d, exist_ok=True)
        out = os.path.join(d, "ic_launcher.png")
        write_png(out, render(size), size)
        print(f"{folder}/ic_launcher.png  {size}x{size}  {os.path.getsize(out)} bytes")


if __name__ == "__main__":
    main()
