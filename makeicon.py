#!/usr/bin/env python3
import math, os, struct, zlib

def make_icon(size):
    px = bytearray()
    for y in range(size):
        for x in range(size):
            fx = (x + 0.5) / size
            fy = (y + 0.5) / size
            r = 16; g = 18; b = 28
            # rounded screen area
            sx0, sy0, sx1, sy1 = 0.08, 0.08, 0.92, 0.92
            rad = 0.06
            in_screen = True
            # corners
            cx = min(max(fx, sx0 + rad), sx1 - rad) if fx > sx0 else -1
            cy = min(max(fy, sy0 + rad), sy1 - rad) if fy > sy0 else -1
            if fx >= sx0 and fx <= sx1 and fy >= sy0 and fy <= sy1:
                if fx < sx0 + rad and fy < sy0 + rad:
                    in_screen = ((fx - (sx0 + rad)) ** 2 + (fy - (sy0 + rad)) ** 2) <= rad * rad
                elif fx > sx1 - rad and fy < sy0 + rad:
                    in_screen = ((fx - (sx1 - rad)) ** 2 + (fy - (sy0 + rad)) ** 2) <= rad * rad
                elif fx < sx0 + rad and fy > sy1 - rad:
                    in_screen = ((fx - (sx0 + rad)) ** 2 + (fy - (sy1 - rad)) ** 2) <= rad * rad
                elif fx > sx1 - rad and fy > sy1 - rad:
                    in_screen = ((fx - (sx1 - rad)) ** 2 + (fy - (sy1 - rad)) ** 2) <= rad * rad
            else:
                in_screen = False
            if in_screen:
                r, g, b = 10, 12, 20
            # bubble 1 (orange)
            b1x, b1y, b1r = 0.36, 0.40, 0.17
            d = math.hypot(fx - b1x, fy - b1y)
            if d <= b1r:
                a = max(0.0, min(1.0, (b1r - d) / (b1r * 0.08)))
                r = int(r + (255 - r) * a); g = int(g + (124 - g) * a); b = int(b + (2 - b) * a)
            # bubble 2 (cyan)
            b2x, b2y, b2r = 0.66, 0.70, 0.11
            d = math.hypot(fx - b2x, fy - b2y)
            if d <= b2r:
                a = max(0.0, min(1.0, (b2r - d) / (b2r * 0.08)))
                r = int(r + (0 - r) * a); g = int(g + (199 - g) * a); b = int(b + (255 - b) * a)
            px += bytes((r, g, b, 255))
    return bytes(px)

def write_png(path, size, px):
    def chunk(t, d):
        c = t + d
        return struct.pack('>I', len(d)) + c + struct.pack('>I', zlib.crc32(c) & 0xffffffff)
    raw = b''.join(b'\x00' + px[row * size * 4:(row + 1) * size * 4] for row in range(size))
    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(raw, 9))
    png += chunk(b'IEND', b'')
    with open(path, 'wb') as f:
        f.write(png)

base = '/root/rpcsv-android/res'
sizes = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}
for name, s in sizes.items():
    write_png(os.path.join(base, 'mipmap-%s' % name, 'ic_launcher.png'), s, make_icon(s))
print('icons written', sizes)