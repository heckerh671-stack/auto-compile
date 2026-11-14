# Bakes the GUI fonts into a multi-channel signed distance field (MSDF) atlas header for zalard_gui.dll.
#
#   python3 tools/gen_atlas.py native/font_atlas.h atlas.png      (needs msdfgen: MSDFGEN=/path/to/msdfgen)
#
# MSDF keeps corners sharp at any size (a plain SDF rounds them off), which matters a lot for a
# blocky pixel font. Every glyph is Zalard's own pixel font (tools/zfont.py) - original hand-drawn
# 5x7 bitmaps, no third-party typeface. Each glyph's pixel outline is handed to msdfgen as a polygon,
# so every font pixel stays a crisp square at any size.
# The atlas is RGB; the shader takes the median of the three channels.
import os, sys, subprocess, tempfile
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
MSDFGEN = os.environ.get("MSDFGEN", os.path.join(HERE, "msdfgen"))

# All faces are the one original pixel font; bold = one pixel wider. The GUI uses FACE_PIX / FACE_PIXB
# almost everywhere, with a few FACE_REG / FACE_SEMI spots - all map to the same pixel font here.
FACES = [
    ("FACE_REG",  "ZFONT", False),
    ("FACE_SEMI", "ZFONT", True),
    ("FACE_MONO", "ZFONT", False),
    ("FACE_PIX",  "ZFONT", False),
    ("FACE_PIXB", "ZFONT", True),
]
CHARS = [chr(c) for c in range(32, 127)] + ["×", "—", "…", "·"]
EM, PAD = 40, 6                  # atlas px per em; distance range / margin in atlas px
PXRANGE = 2 * PAD
GRID_PX = 4                      # atlas px per font pixel of the pixel faces (EM / 10)
ATLAS_W = 1024

def run_msdf(args, w, h):
    with tempfile.TemporaryDirectory() as td:
        out = os.path.join(td, "g.bin")
        cmd = [MSDFGEN, "msdf"] + args + ["-dimensions", str(w), str(h), "-pxrange", str(PXRANGE),
                                         "-format", "binfloat", "-o", out]
        subprocess.run(cmd, check=True, capture_output=True)
        a = np.fromfile(out, dtype="<f4").reshape(h, w, 3)
    a = np.flipud(a)                                         # msdfgen rows go bottom -> top
    return (np.clip(a, 0, 1) * 255 + 0.5).astype(np.uint8)

def pixel_contours(m, left, top):
    """Outline of a pixel bitmap (rows top->bottom) as polygons in font-pixel units, y up, CCW outside."""
    hh, ww = m.shape
    edges = {}
    def add(a, b):
        if (b, a) in edges: del edges[(b, a)]
        else: edges[(a, b)] = True
    for r in range(hh):
        for c in range(ww):
            if not m[r, c]: continue
            x0, y0 = left + c, top - r                         # cell spans [x0,x0+1] x [y0,y0+1]
            p = [(x0, y0), (x0 + 1, y0), (x0 + 1, y0 + 1), (x0, y0 + 1)]
            for i in range(4): add(p[i], p[(i + 1) % 4])
    out_of = {}
    for (a, b) in edges: out_of.setdefault(a, []).append(b)
    loops = []
    while out_of:
        start = next(iter(out_of))
        loop = [start]; cur = start; prev = None
        while True:
            nxt = out_of[cur]
            if len(nxt) > 1 and prev is not None:          # pinch point: take the left-most turn
                dx, dy = cur[0] - prev[0], cur[1] - prev[1]
                def turn(n):
                    ex, ey = n[0] - cur[0], n[1] - cur[1]
                    return -(dx * ey - dy * ex)
                nxt.sort(key=turn)
            n = nxt.pop(0)
            if not nxt: del out_of[cur]
            prev, cur = cur, n
            if cur == start: break
            loop.append(cur)
        # drop the corners in the middle of straight runs
        pts = []
        L = len(loop)
        for i in range(L):
            a, b, c = loop[i - 1], loop[i], loop[(i + 1) % L]
            if (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0]) != 0: pts.append(b)
        loops.append(pts)
    return loops

sys.path.insert(0, HERE)
import zfont

glyphs = []   # (face, idx, img(h,w,3) uint8 or None, xoff, yoff, adv)   metrics in em units
cap = {}
emf = EM / GRID_PX                                           # 10 font pixels per em (PIX_EMF)
for fi, face in enumerate(FACES):
    bold = face[2]
    for ci, ch in enumerate(CHARS):
        a, left, top, advu = zfont.glyph(ch)
        if a is None:
            glyphs.append((fi, ci, None, 0, 0, max(3, advu) / emf)); continue
        if bold:
            z = np.zeros((a.shape[0], 1), bool)
            a = np.hstack([a, z]) | np.hstack([z, a])
        hh, ww = a.shape
        loops = pixel_contours(a, left, top)
        desc = " ".join("{ " + "; ".join(f"{x}, {y}" for x, y in L) + "; # }" for L in loops)
        W, H = ww * GRID_PX + 2 * PAD, hh * GRID_PX + 2 * PAD
        bottom = top + 1 - hh
        args = ["-defineshape", desc, "-scale", str(GRID_PX),
                "-translate", str(PAD / GRID_PX - left), str(PAD / GRID_PX - bottom), "-angle", "1D"]
        img = run_msdf(args, W, H)
        glyphs.append((fi, ci, img, (left * GRID_PX - PAD) / EM, (-(top + 1) * GRID_PX - PAD) / EM,
                       (left + ww + 1) / emf))
    cap[fi] = 7 / emf

# shelf pack
x = y = rowh = 0
place = {}
for g in sorted([g for g in glyphs if g[2] is not None], key=lambda g: -g[2].shape[0]):
    h, w = g[2].shape[:2]
    if x + w > ATLAS_W: x, y, rowh = 0, y + rowh + 1, 0
    place[(g[0], g[1])] = (x, y)
    x += w + 1; rowh = max(rowh, h)
ATLAS_H = 1
while ATLAS_H < y + rowh: ATLAS_H *= 2
atlas = np.zeros((ATLAS_H, ATLAS_W, 3), np.uint8)
for g in glyphs:
    if g[2] is None: continue
    px, py = place[(g[0], g[1])]
    h, w = g[2].shape[:2]
    atlas[py:py + h, px:px + w] = g[2]
Image.fromarray(atlas, "RGB").save(sys.argv[2])

out = open(sys.argv[1], "w")
out.write("// Generated by tools/gen_atlas.py - do not edit. MSDF (RGB) atlas from msdfgen.\n#pragma once\n")
out.write(f"#define ATLAS_W {ATLAS_W}\n#define ATLAS_H {ATLAS_H}\n#define ATLAS_CH 3\n#define GLYPH_COUNT {len(CHARS)}\n#define ATLAS_EM {EM}\n")
for i, f in enumerate(FACES): out.write(f"#define {f[0]} {i}\n")
out.write("#define PIX_EMF 10.0f\n")
out.write("static const unsigned int EXTRA_CPS[] = {" + ",".join(str(ord(c)) for c in CHARS[95:]) + "};\n")
out.write("static const float CAP_H[] = {" + ",".join(f"{cap[i]:.5f}f" for i in range(len(FACES))) + "};\n")
out.write("struct GlyphInfo { short x, y, w, h; float xoff, yoff, adv; };\n")
out.write(f"static const GlyphInfo GLYPHS[{len(FACES)}][{len(CHARS)}] = {{\n")
for fi in range(len(FACES)):
    out.write(" {")
    for g in [g for g in glyphs if g[0] == fi]:
        if g[2] is None: out.write(f"{{0,0,0,0,0,0,{g[5]:.5f}f}},")
        else:
            px, py = place[(g[0], g[1])]; h, w = g[2].shape[:2]
            out.write(f"{{{px},{py},{w},{h},{g[3]:.5f}f,{g[4]:.5f}f,{g[5]:.5f}f}},")
    out.write("},\n")
out.write("};\n")
out.write(f"static const unsigned char ATLAS_PIXELS[{ATLAS_W * ATLAS_H * 3}] = {{")
flat = atlas.ravel()
for i in range(0, len(flat), 64):
    out.write("\n" + ",".join(map(str, flat[i:i + 64])) + ",")
out.write("\n};\n")
print("atlas", ATLAS_W, "x", ATLAS_H, "glyphs", len(glyphs))
