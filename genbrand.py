#!/usr/bin/env python3
"""Gera do zero a arte do RPCSV: icones do launcher, marca do boot e o som.

Nada aqui vem do Vita3K: o PNG e o WAV sao sintetizados pixel a pixel /
amostra a amostra. Substitui o makeicon.py, que so desenhavava dois
circulos genericos e perdia a cor em xhdpi (aapt2 o guardava como
grayscale, sem alpha).

Saidas:
  <ANDROID>/res/mipmap-*/ic_launcher.png          (quadrado arredondado)
  <ANDROID>/res/mipmap-*/ic_launcher_round.png     (circulo)
  <ANDROID>/res/mipmap-*/ic_launcher_foreground.png (adaptive, 108dp, fundo transparente)
  <ANDROID>/res/mipmap-*/ic_launcher_background.png (adaptive, 108dp)
  <ANDROID>/res/drawable-nodpi/rpcsv_mark.png      (layer-list do splash)
  <RENDERER>/brand/rpcsv-mark.png                  (HTML do boot)
  <RENDERER>/brand/boot.wav                        (som do boot)

Uso: python3 genbrand.py
"""

import math
import os
import struct
import wave
import zlib

ANDROID = os.path.dirname(os.path.abspath(__file__))
RENDERER = "/root/rpcsv/renderer"

# ----------------------------------------------------------------- paleta
# Mesma linguagem do renderer/css: --vita-blue #2b9df0 sobre fundo escuro.
NAVY_IN = (0x16, 0x27, 0x45)
NAVY_OUT = (0x05, 0x0A, 0x12)
BLUE = (0x2B, 0x9D, 0xF0)
BLUE_DK = (0x0E, 0x50, 0xA0)
ICE = (0xEA, 0xF6, 0xFF)
PALE = (0x9F, 0xD4, 0xFF)
CYAN = (0x7F, 0xDC, 0xFF)

# Geometria da marca, em espaco normalizado 0..1.
ORB_CX, ORB_CY, ORB_R = 0.500, 0.455, 0.278
RING_A, RING_B, RING_W = 0.452, 0.408, 0.049
RING_TH = math.radians(-21.0)
SAT_ANG = math.radians(41.0)
SAT_R = 0.062

SS = 4  # supersampling por eixo


def clamp(v, lo=0.0, hi=1.0):
    return lo if v < lo else (hi if v > hi else v)


def mix(a, b, t):
    t = clamp(t)
    return (a[0] + (b[0] - a[0]) * t,
            a[1] + (b[1] - a[1]) * t,
            a[2] + (b[2] - a[2]) * t)


def mix4(dst, src, t):
    """Mistura as cores de dois RGBA mantendo o alfa do destino.

    Usado nos brilhos, que clareiam o que ja foi pintado sem criar
    cobertura nova — se o alfa subisse junto, o fundo transparente do
    adaptive icon ganharia um retangulo fantasma.
    """
    c = mix(dst, src, t)
    return (c[0], c[1], c[2], dst[3])


def over4(dst, src, sa):
    """Source-over real entre dois RGBA (alfa em 0..1).

    `over` original assumia dst opaco, o que impedia um foreground de
    adaptive icon: sem fundo, ele voltava a ser um quadrado opaco e a
    mascara circular do launcher cortava a arte errada.
    """
    sa = clamp(sa)
    if sa <= 0.0:
        return dst
    da = clamp(dst[3])
    if sa >= 1.0 and da >= 1.0:
        return (src[0], src[1], src[2], 1.0)
    oa = sa + da * (1.0 - sa)
    if oa <= 0.0:
        return (0.0, 0.0, 0.0, 0.0)
    r = (src[0] * sa + dst[0] * da * (1.0 - sa)) / oa
    g = (src[1] * sa + dst[1] * da * (1.0 - sa)) / oa
    b = (src[2] * sa + dst[2] * da * (1.0 - sa)) / oa
    return (r, g, b, oa)


def opaque(rgb):
    return (rgb[0], rgb[1], rgb[2], 1.0)


# ------------------------------------------------------------- a marca
def _bg(fx, fy):
    d = math.hypot(fx - 0.5, fy - 0.46) / 0.74
    c = mix(NAVY_IN, NAVY_OUT, clamp(d) ** 0.82)
    glow = math.exp(-(((fx - 0.5) ** 2) + ((fy - 0.455) ** 2)) / (2 * 0.215 ** 2))
    c = mix(c, (0x1B, 0x4A, 0x82), 0.58 * glow)
    return c


def _orb_color(nx, ny):
    d = clamp(math.hypot(nx, ny))
    c = mix(ICE, BLUE, clamp(d * 1.30))
    c = mix(c, BLUE_DK, clamp((d - 0.58) / 0.42))
    hl = math.hypot(nx + 0.33, ny + 0.36)
    c = mix(c, (255, 255, 255), 0.80 * math.exp(-(hl * hl) / (2 * 0.17 ** 2)))
    return c


def _sample_mark(fx, fy, mark_scale, mark_cx, mark_cy, bg_alpha):
    """Cor RGBA da marca (fundo + orbe + anel + satelite) num ponto.

    ``mark_scale`` encolhe a marca dentro do canvas (adaptive icon precisa
    ficar na safe zone de 66%), ``mark_cx/cy`` centralityam e ``bg_alpha``
    controla a cobertura do fundo — 1.0 para os icones legacy, 0.0 para o
    foreground do adaptive icon e para a marca sobre a tela de boot.
    """
    b = _bg(fx, fy)
    c = (b[0], b[1], b[2], bg_alpha)

    # volta o espaco da marca
    gx = (fx - mark_cx) / mark_scale + 0.5
    gy = (fy - mark_cy) / mark_scale + 0.5

    dx, dy = gx - ORB_CX, gy - ORB_CY

    # --- anel orbital (elipse girada) ---
    ca, sa = math.cos(RING_TH), math.sin(RING_TH)
    u = (dx * ca + dy * sa) / RING_A
    v = (-dx * sa + dy * ca) / RING_B
    rr = math.hypot(u, v)
    half = (RING_W * 0.5) / min(RING_A, RING_B)
    d_ring = abs(rr - 1.0)
    if d_ring < half:
        # borda externa mais fraca, miolo mais claro
        t = 1.0 - (d_ring / half) ** 2
        ang = math.atan2(dy, dx)
        grad = 0.5 + 0.5 * math.cos(ang - 0.6)
        rc = mix(PALE, ICE, grad)
        a = t * 0.96
        # halo do anel
        if half < d_ring < half * 3.2:
            h = 1.0 - (d_ring - half) / (half * 2.2)
            c = mix4(c, mix4(c, PALE, 0.55), clamp(h) * 0.42)
        c = over4(c, opaque(rc), a)

    # --- orbe ---
    d_orb = math.hypot(dx, dy)
    if d_orb < ORB_R:
        # antialias pela propria corda
        a = clamp((ORB_R - d_orb) / (ORB_R * 0.055 + 1e-6))
        oc = _orb_color(dx / ORB_R, dy / ORB_R)
        c = over4(c, opaque(oc), a)
        # brilho externo
        if ORB_R <= d_orb < ORB_R * 1.62:
            h = 1.0 - (d_orb - ORB_R) / (ORB_R * 0.62)
            c = mix4(c, mix4(c, PALE, 0.60), clamp(h) ** 1.6 * 0.40)

    # --- satelite no anel ---
    sx = ORB_CX + math.cos(SAT_ANG) * RING_A
    sy = ORB_CY + math.sin(SAT_ANG) * RING_B
    dsat = math.hypot(gx - sx, gy - sy)
    if dsat < SAT_R:
        a = clamp((SAT_R - dsat) / (SAT_R * 0.10 + 1e-6))
        c = over4(c, opaque(CYAN), a)
    elif dsat < SAT_R * 2.4:
        h = 1.0 - (dsat - SAT_R) / (SAT_R * 1.4)
        c = mix4(c, mix4(c, CYAN, 0.5), clamp(h) * 0.30)

    return c


def render(size, shape="squircle", mark_scale=1.0, mark_cx=0.5, mark_cy=0.5,
           want_bg=True, bg_alpha=1.0, corner=0.225):
    """Renderiza RGBA (sempre color type 6, com alpha de verdade)."""
    n = size * SS
    buf = bytearray(n * n * 4)
    inv = 1.0 / n

    for py in range(n):
        y = (py + 0.5) * inv
        for px in range(n):
            x = (px + 0.5) * inv

            # mascara da forma
            if shape == "circle":
                d = math.hypot(x - 0.5, y - 0.5) - 0.5
                inside = d <= 0.0
                a = clamp(0.5 - d * n)
            elif shape == "none":
                inside, a = True, 1.0
            else:
                # Squircle = caixa com cantos arredondados (SDF padrao):
                #   q = |p| - meia_extensao + raio
                #   d = |max(q,0)| + min(max(q.x,q.y),0) - raio
                # Sem o `- raio` final o desenho vira uma caixa reta de
                # ~(1 - 2*raio) de lado, e o "icone" saia um quadradinho
                # desfocado no meio do canvas em vez de um icone.
                r = corner
                qx = abs(x - 0.5) - (0.5 - r)
                qy = abs(y - 0.5) - (0.5 - r)
                d = (math.hypot(max(qx, 0.0), max(qy, 0.0))
                     + min(max(qx, qy), 0.0) - r)
                inside = d <= 0.0
                a = clamp(0.5 - d * n)

            if not inside and a <= 0.0:
                continue
            c = _sample_mark(x, y, mark_scale, mark_cx, mark_cy,
                             bg_alpha if want_bg else 0.0)
            i = (py * n + px) * 4
            buf[i] = int(c[0] + 0.5)
            buf[i + 1] = int(c[1] + 0.5)
            buf[i + 2] = int(c[2] + 0.5)
            buf[i + 3] = int(255 * clamp(a * c[3]) + 0.5)

    # downsample SSxSS -> media (antialias de verdade, nao so um unico sample)
    out = bytearray(size * size * 4)
    inv4 = 1.0 / (SS * SS)
    for y in range(size):
        for x in range(size):
            r = g = b = al = 0.0
            for sy in range(SS):
                base = ((y * SS + sy) * n + x * SS) * 4
                for sx in range(SS):
                    i = base + sx * 4
                    av = buf[i + 3] / 255.0
                    r += buf[i] * av
                    g += buf[i + 1] * av
                    b += buf[i + 2] * av
                    al += av
            o = (y * size + x) * 4
            if al <= 0.0:
                continue
            # media premultiplicada -> desmultiplica
            out[o] = min(255, int(r / al + 0.5))
            out[o + 1] = min(255, int(g / al + 0.5))
            out[o + 2] = min(255, int(b / al + 0.5))
            out[o + 3] = min(255, int(al * 255 * inv4 + 0.5))
    return bytes(out)


def write_png(path, size, px):
    def chunk(t, d):
        c = t + d
        return struct.pack(">I", len(d)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    raw = b"".join(
        b"\x00" + px[row * size * 4:(row + 1) * size * 4] for row in range(size)
    )
    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)
    return len(png)


# ------------------------------------------------------------- som do boot
def make_boot_wav(path, rate=22050, dur=2.45):
    """Acorde maior subindo + sub grave, com envolve suave (sem clicks).

    Sequencia de um "power on": A3 (sub) -> A4 -> C#5 -> E5 -> A5, cada nota
    com ataque de 12 ms e decaimento exponencial; a soma recebe um fade-in e
    um fade-out global.
    """
    n = int(rate * dur)
    notes = [
        (220.00, 0.00, 0.90, 0.30),   # A3  - graves que dao corpo
        (440.00, 0.16, 0.85, 0.34),   # A4
        (554.37, 0.34, 0.80, 0.30),   # C#5
        (659.26, 0.52, 0.75, 0.27),   # E5
        (880.00, 0.72, 1.05, 0.30),   # A5 -resolve
    ]
    buf = [0.0] * n
    for freq, t0, life, amp in notes:
        start = int(t0 * rate)
        ln = int(life * rate)
        for k in range(ln):
            i = start + k
            if i >= n:
                break
            t = k / float(rate)
            env = math.exp(-t * 3.1) * (1.0 - math.exp(-t / 0.012))
            # leve batimento de dois Harmonicos, como um "bell" de console
            s = (math.sin(2 * math.pi * freq * t)
                 + 0.30 * math.sin(4 * math.pi * freq * t) * math.exp(-t * 5.5)
                 + 0.11 * math.sin(6 * math.pi * freq * t) * math.exp(-t * 8.0))
            buf[i] += s * env * amp

    # normaliza
    peak = max(1e-9, max(abs(v) for v in buf))
    norm = 0.86 / peak
    # fade global (30 ms) para nao estalar no fim
    fi = int(0.030 * rate)
    fo = int(0.38 * rate)
    frames = bytearray()
    for i, v in enumerate(buf):
        g = norm
        if i < fi:
            g *= i / float(fi)
        if i > n - fo:
            g *= (n - i) / float(fo)
        s = int(max(-1.0, min(1.0, v * g)) * 32000)
        frames += struct.pack("<h", s)

    os.makedirs(os.path.dirname(path), exist_ok=True)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(bytes(frames))
    return len(frames)


# ------------------------------------------------------------------ main
def main():
    legacy = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    adaptive = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

    # 1. icones legados (quadrado + circulo)
    for d, s in legacy.items():
        base = os.path.join(ANDROID, "res", "mipmap-" + d)
        write_png(os.path.join(base, "ic_launcher.png"), s,
                  render(s, "squircle", mark_scale=0.90, mark_cy=0.50))
        write_png(os.path.join(base, "ic_launcher_round.png"), s,
                  render(s, "circle", mark_scale=0.84, mark_cy=0.50))
        print("  mipmap-%-7s %3dpx  launcher + round" % (d, s))

    # 2. adaptive icon: 108dp de canvas, marca dentro da safe zone de 66%
    # O foreground tem de sair TRANSPARENTE: o launcher recorta as duas
    # camadas com a mesma mascara, e um foreground opaco esconderia o
    # background e cortaria a arte no formato do quadrado em vez do circulo.
    safe = 0.66
    for d, s in adaptive.items():
        base = os.path.join(ANDROID, "res", "mipmap-" + d)
        write_png(os.path.join(base, "ic_launcher_background.png"), s,
                  render(s, "none", mark_scale=1.0, want_bg=True))
        write_png(os.path.join(base, "ic_launcher_foreground.png"), s,
                  render(s, "none", want_bg=True, bg_alpha=0.0, mark_scale=safe,
                         mark_cx=0.5, mark_cy=0.5))
        print("  mipmap-%-7s %3dpx  adaptive fg/bg" % (d, s))

    # 3. marca para o layer-list do splash nativo
    n = write_png(os.path.join(ANDROID, "res", "drawable-nodpi", "rpcsv_mark.png"),
                  384, render(384, "none", want_bg=True, bg_alpha=0.0, mark_scale=0.78))
    print("  drawable-nodpi/rpcsv_mark.png  (%d bytes)" % n)

    # 4. assets do boot no WebView (o fundo e preto, entao fundo transparente)
    n = write_png(os.path.join(RENDERER, "brand", "rpcsv-mark.png"),
                  512, render(512, "none", want_bg=True, bg_alpha=0.0, mark_scale=0.86))
    print("  renderer/brand/rpcsv-mark.png  (%d bytes)" % n)
    n = make_boot_wav(os.path.join(RENDERER, "brand", "boot.wav"))
    print("  renderer/brand/boot.wav         (%d bytes)" % n)
    print("marca + som gerados do zero.")


if __name__ == "__main__":
    main()
