#!/usr/bin/env python3
"""Plait Broker icon. A three-strand plait runs corner to corner: amber on the app's side, cream on the system's side.
Where the strands cross in the middle (the hand-off, i.e. the broker) the two colours fade into each other and the
crossover glows. The strands cross over and under as in a real plait: the outer strand always goes over the middle
one, alternating sides.

Writes the SVG master and the adaptive-icon vector drawables (foreground, background, monochrome) into OUTDIR.
The over/under gaps are real cuts in the strands, so the monochrome layer is the same plait in one colour; there the
glow is an alpha-faded halo (themed icons cannot show colour, but they do keep alpha).

Usage: python3 icon/make-icon.py OUTDIR
       then copy OUTDIR/ic_launcher_*.xml to broker/src/main/res/drawable/ and OUTDIR/plait-broker-icon.svg to icon/.
Every numeric parameter can be overridden with an environment variable of the same name (e.g. ANGLE=0)."""
import math, sys, os

def env(name, default):
    return float(os.environ.get(name, default))

BG = os.environ.get("BG", "#1C1F4A")         # deep indigo
IN_C = os.environ.get("IN_C", "#F2B544")     # amber: the app's side
OUT_C = os.environ.get("OUT_C", "#FFF3D6")   # cream: the system's side
GLOW_C = os.environ.get("GLOW_C", "FFF3D6")  # glow colour (RRGGBB; alpha comes from GLOW_A)

C = 54.0
ANGLE = env("ANGLE", -45)        # direction of flow in degrees (-45: bottom-left to top-right)
W = env("W", 5.8)                # strand width
GAP = env("GAP", 1.5)            # clearance where a strand passes under another
SPREAD = env("SPREAD", 10.0)     # distance of the outer strands from the axis
BULGE = env("BULGE", 2.8)        # control offset of the idle outer strand while the other two cross
STEP = env("STEP", 8.0)         # length of one crossing
CROSSINGS = int(env("CROSSINGS", 7))   # odd, so a crossing sits exactly at the centre
FADE = env("FADE", 9.0)          # half-length of the amber-to-cream fade along the axis
GLOW_R = env("GLOW_R", 21.0)     # glow radius
GLOW_A = env("GLOW_A", 0.8)     # glow opacity at its centre
MONO_A = env("MONO_A", 0.4)      # monochrome: opacity of the halo behind the crossover
MIN_PIECE = env("MIN_PIECE", 0.6)  # drop slivers shorter than this
MIN_END = env("MIN_END", 3.0)      # drop a free end (and its dot) if its visible stub is shorter than this
TOP_R = env("TOP_R", 9.0)          # radius of the highlight drawn over the crossover
TOP_A = env("TOP_A", 0.4)         # its opacity at the centre
N = 200

def bez(p0, p1, p2, p3):
    out = []
    for i in range(N + 1):
        t = i / N
        mt = 1 - t
        out.append(tuple(mt ** 3 * a + 3 * mt * mt * t * b + 3 * mt * t * t * c + t ** 3 * d
                         for a, b, c, d in zip(p0, p1, p2, p3)))
    return out

def dist_to_poly(p, poly):
    best = 1e9
    for (ax, ay), (bx, by) in zip(poly, poly[1:]):
        dx, dy = bx - ax, by - ay
        L = dx * dx + dy * dy
        u = 0 if L == 0 else max(0, min(1, ((p[0] - ax) * dx + (p[1] - ay) * dy) / L))
        best = min(best, math.hypot(p[0] - ax - u * dx, p[1] - ay - u * dy))
    return best

ca, sa = math.cos(math.radians(ANGLE)), math.sin(math.radians(ANGLE))
def to_xy(u, v):              # u along the flow, v across it
    return (C + u * ca - v * sa, C + u * sa + v * ca)

# Build the plait in (u, v): each step swaps the middle slot with an outer one, alternating sides.
slot = [0, 1, 2]
strands = [[] for _ in range(3)]
crossings = []                # (first index, last index, over strand, under strand)
u = -STEP * CROSSINGS / 2
for s in range(CROSSINGS):
    outer = 0 if s % 2 == 0 else 2
    nxt = slot[:]
    oi, mi = slot.index(outer), slot.index(1)
    nxt[oi], nxt[mi] = 1, outer
    a, b, h = u, u + STEP, STEP / 2
    lo = len(strands[0]) - 1 if strands[0] else 0
    for i in range(3):
        v0, v1 = (slot[i] - 1) * SPREAD, (nxt[i] - 1) * SPREAD
        if i in (oi, mi):
            seg = bez((a, v0), (a + h, v0), (b - h, v1), (b, v1))
        else:                 # the idle outer strand bows outward
            out = (slot[i] - 1) * BULGE
            seg = bez((a, v0), (a + STEP * 0.3, v0 + out), (b - STEP * 0.3, v1 + out), (b, v1))
        strands[i].extend(seg if not strands[i] else seg[1:])
    crossings.append((lo, lo + N, oi, mi))   # the outer strand goes over the middle one
    slot = nxt
    u = b

mask = [[False] * len(strands[i]) for i in range(3)]
for lo, hi, over, under in crossings:
    o = strands[over][max(0, lo - N // 2):hi + N // 2]
    us = strands[under]
    i = min(range(lo, hi + 1), key=lambda m: dist_to_poly(us[m], strands[over][lo:hi + 1]))
    for d in (-1, 1):
        m = i
        while 0 <= m < len(us) and dist_to_poly(us[m], o) < W / 2 + GAP:
            mask[under][m] = True
            m += d

def seglen(poly):
    return sum(math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in zip(poly, poly[1:]))

pieces, free_ends = [], []
for i in range(3):
    cur = []
    for m, p in enumerate(strands[i] + [None]):
        if p is None or mask[i][m]:
            ends_here = [q for q in (cur[0], cur[-1]) if q in (strands[i][0], strands[i][-1])] if cur else []
            if seglen(cur) >= (MIN_END if ends_here else MIN_PIECE):
                pieces.append([to_xy(*q) for q in cur])
                free_ends += ends_here
            cur = []
        else:
            cur.append(p)

def r2(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")

def path_d(poly, step=8):
    keep = poly[::step]
    if keep[-1] != poly[-1]:
        keep.append(poly[-1])
    return "M" + " L".join(f"{r2(x)},{r2(y)}" for x, y in keep)

def circle(x, y, r):
    return f"M{r2(x - r)},{r2(y)} a{r2(r)},{r2(r)} 0 1,0 {r2(2 * r)},0 a{r2(r)},{r2(r)} 0 1,0 {r2(-2 * r)},0 Z"

strand_d = [path_d(p) for p in pieces]
end_d = [circle(*to_xy(*p), W / 2) for p in free_ends]   # rounded free ends; cut ends stay square
glow_d = circle(C, C, GLOW_R)
spark_d = circle(C, C, TOP_R)
fx0, fy0 = to_xy(-FADE, 0)
fx1, fy1 = to_xy(FADE, 0)
glow_hex = f"{round(GLOW_A * 255):02X}{GLOW_C}"

mx = max(math.hypot(x - C, y - C) + W / 2 for p in pieces for x, y in p)
print("outer radius incl. stroke:", round(mx, 2), "(safe zone 33)", file=sys.stderr)
if mx > 33 or GLOW_R > 33:
    sys.exit("art leaves the 66dp safe zone")

out = sys.argv[1]
os.makedirs(out, exist_ok=True)

# SVG master
svg = ['<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">',
       '  <title>Plait Broker</title>',
       '  <!-- Adaptive-icon master (108x108, art inside the central 66-unit safe zone): a plait from the app (amber)',
       '       fades into a plait to the system (cream) where the strands cross; the crossover glows.',
       '       Generated by make-icon.py. -->',
       '  <defs>',
       f'    <linearGradient id="handoff" gradientUnits="userSpaceOnUse" x1="{r2(fx0)}" y1="{r2(fy0)}" x2="{r2(fx1)}" y2="{r2(fy1)}">',
       f'      <stop offset="0" stop-color="{IN_C}"/>',
       f'      <stop offset="1" stop-color="{OUT_C}"/>',
       '    </linearGradient>',
       f'    <radialGradient id="glow" gradientUnits="userSpaceOnUse" cx="{r2(C)}" cy="{r2(C)}" r="{r2(GLOW_R)}">',
       f'      <stop offset="0" stop-color="#{GLOW_C}" stop-opacity="{r2(GLOW_A)}"/>',
       f'      <stop offset="0.45" stop-color="#{GLOW_C}" stop-opacity="{r2(GLOW_A * 0.35)}"/>',
       f'      <stop offset="1" stop-color="#{GLOW_C}" stop-opacity="0"/>',
       '    </radialGradient>',
       '  </defs>',
       f'  <rect width="108" height="108" fill="{BG}"/>',
       f'  <path fill="url(#glow)" d="{glow_d}"/>',
       f'  <g fill="none" stroke="url(#handoff)" stroke-width="{r2(W)}" stroke-linecap="butt" stroke-linejoin="round">']
svg += [f'    <path d="{d}"/>' for d in strand_d]
svg += ['  </g>', '  <g fill="url(#handoff)">']
svg += [f'    <path d="{d}"/>' for d in end_d]
svg += ['  </g>', f'  <path fill="url(#spark)" d="{spark_d}"/>', '</svg>', '']
open(f"{out}/plait-broker-icon.svg", "w").write("\n".join(svg))

# Vector drawables
HEAD = ['<?xml version="1.0" encoding="utf-8"?>']
VEC = ('<vector xmlns:android="http://schemas.android.com/apk/res/android"{extra}\n'
       '    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">')

def handoff_gradient(attr, indent):
    return [f'{indent}<aapt:attr name="android:{attr}">',
            f'{indent}    <gradient android:type="linear" android:startX="{r2(fx0)}" android:startY="{r2(fy0)}"'
            f' android:endX="{r2(fx1)}" android:endY="{r2(fy1)}"',
            f'{indent}        android:startColor="{IN_C}" android:endColor="{OUT_C}" android:tileMode="clamp" />',
            f'{indent}</aapt:attr>']

fg = HEAD + ['<!-- Plait Broker: a plait from the app (amber) fades into a plait to the system (cream) where the strands '
             'cross; the crossover glows. Generated by icon/make-icon.py -->',
             VEC.format(extra='\n    xmlns:aapt="http://schemas.android.com/aapt"'),
             f'    <path android:pathData="{glow_d}">',
             '        <aapt:attr name="android:fillColor">',
             f'            <gradient android:type="radial" android:centerX="{r2(C)}" android:centerY="{r2(C)}"'
             f' android:gradientRadius="{r2(GLOW_R)}">',
             f'                <item android:offset="0" android:color="#{glow_hex}" />',
             f'                <item android:offset="0.45" android:color="#{round(GLOW_A * 0.35 * 255):02X}{GLOW_C}" />',
             f'                <item android:offset="1" android:color="#00{GLOW_C}" />',
             '            </gradient>',
             '        </aapt:attr>',
             '    </path>']
for d in strand_d:
    fg += [f'    <path android:pathData="{d}" android:strokeWidth="{r2(W)}" android:strokeLineCap="butt"'
           ' android:strokeLineJoin="round">'] + handoff_gradient("strokeColor", "        ") + ['    </path>']
for d in end_d:
    fg += [f'    <path android:pathData="{d}">'] + handoff_gradient("fillColor", "        ") + ['    </path>']
fg += [f'    <path android:pathData="{spark_d}">',
       '        <aapt:attr name="android:fillColor">',
       f'            <gradient android:type="radial" android:centerX="{r2(C)}" android:centerY="{r2(C)}"'
       f' android:gradientRadius="{r2(TOP_R)}">',
       f'                <item android:offset="0" android:color="#{round(TOP_A * 255):02X}{GLOW_C}" />',
       f'                <item android:offset="1" android:color="#00{GLOW_C}" />',
       '            </gradient>',
       '        </aapt:attr>',
       '    </path>',
       '</vector>', '']
open(f"{out}/ic_launcher_foreground.xml", "w").write("\n".join(fg))

mono = HEAD + ['<!-- Themed (monochrome) icon: the same plait in one colour (the over/under gaps are real cuts), over an '
               'alpha-faded halo where the colour icon glows. Generated by icon/make-icon.py -->',
               VEC.format(extra='\n    xmlns:aapt="http://schemas.android.com/aapt"'),
               f'    <path android:pathData="{glow_d}">',
               '        <aapt:attr name="android:fillColor">',
               f'            <gradient android:type="radial" android:centerX="{r2(C)}" android:centerY="{r2(C)}"'
               f' android:gradientRadius="{r2(GLOW_R)}">',
               f'                <item android:offset="0" android:color="#{round(MONO_A * 255):02X}FFFFFF" />',
               f'                <item android:offset="0.45" android:color="#{round(MONO_A * 0.35 * 255):02X}FFFFFF" />',
               '                <item android:offset="1" android:color="#00FFFFFF" />',
               '            </gradient>',
               '        </aapt:attr>',
               '    </path>']
for d in strand_d:
    mono.append(f'    <path android:pathData="{d}" android:strokeColor="#FFFFFFFF" android:strokeWidth="{r2(W)}"'
                ' android:strokeLineCap="butt" android:strokeLineJoin="round" />')
for d in end_d:
    mono.append(f'    <path android:pathData="{d}" android:fillColor="#FFFFFFFF" />')
mono += ['</vector>', '']
open(f"{out}/ic_launcher_monochrome.xml", "w").write("\n".join(mono))

# monochrome preview SVG (not installed; used to check the themed icon)
msvg = ['<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">',
        f'  <defs><radialGradient id="h" gradientUnits="userSpaceOnUse" cx="{r2(C)}" cy="{r2(C)}" r="{r2(GLOW_R)}">'
        f'<stop offset="0" stop-color="#3A4A6B" stop-opacity="{r2(MONO_A)}"/>'
        f'<stop offset="0.45" stop-color="#3A4A6B" stop-opacity="{r2(MONO_A * 0.35)}"/>'
        '<stop offset="1" stop-color="#3A4A6B" stop-opacity="0"/></radialGradient></defs>',
        '  <rect width="108" height="108" fill="#D8E2FF"/>',
        f'  <path fill="url(#h)" d="{glow_d}"/>',
        f'  <g fill="none" stroke="#3A4A6B" stroke-width="{r2(W)}" stroke-linecap="butt" stroke-linejoin="round">']
msvg += [f'    <path d="{d}"/>' for d in strand_d]
msvg += ['  </g>', '  <g fill="#3A4A6B">']
msvg += [f'    <path d="{d}"/>' for d in end_d]
msvg += ['  </g>', '</svg>', '']
open(f"{out}/monochrome-preview.svg", "w").write("\n".join(msvg))

open(f"{out}/ic_launcher_background.xml", "w").write("\n".join(HEAD + [
    VEC.format(extra=''),
    f'    <path android:fillColor="{BG}" android:pathData="M0,0h108v108h-108z" />',
    '</vector>', '']))
