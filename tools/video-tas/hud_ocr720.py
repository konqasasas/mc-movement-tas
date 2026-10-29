"""Reads the X, Y, Z and F (yaw) lines of a coordinate HUD that shows 5 decimals, frame by frame.

For videos without a tick counter (verdure, 1280x720, font pixel about 1.6): each glyph is matched
against the vanilla font rendered at that scale with its drop shadow, at sub-pixel offsets. A number
is optional '-', 1 to 4 integer digits, '.', then 5 decimals; every layout is tried and the best
average match wins. Writes one JSON row per frame: [frame, x, y, z, yaw, min margin].

usage: python3 hud_ocr720.py VIDEO FIRST_FRAME END_FRAME OUT.json
"""
import json, subprocess, sys
import numpy as np

GLYPHS = {
    '0': ".###. #...# #..## #.#.# ##..# #...# .###.",
    '1': "..#.. .##.. ..#.. ..#.. ..#.. ..#.. #####",
    '2': ".###. #...# ....# ..##. .#... #...# #####",
    '3': ".###. #...# ....# ..##. ....# #...# .###.",
    '4': "...## ..#.# .#..# #...# ##### ....# ....#",
    '5': "##### #.... ####. ....# ....# #...# .###.",
    '6': "..##. .#... #.... ####. #...# #...# .###.",
    '7': "##### #...# ....# ...#. ..#.. ..#.. ..#..",
    '8': ".###. #...# #...# .###. #...# #...# .###.",
    '9': ".###. #...# #...# .#### ....# ...#. .##..",
    '-': "..... ..... ..... ##### ..... ..... .....",
}
GB = {k: np.array([[c == '#' for c in row] for row in v.split()]) for k, v in GLYPHS.items()}
DIGITS = [k for k in GB if k != '-']

S = 1.6
X0 = 22.5
YS = (30.5, 48.0, 65.75, 81.5)
W, H = 11, 14
_cache = {}


def render(g, ox, oy, ss=4):
    t = np.zeros((H, W))
    for py in range(H):
        for px in range(W):
            acc = 0
            for sy in range(ss):
                for sx in range(ss):
                    u = int(np.floor((px + (sx + .5) / ss - ox) / S))
                    v = int(np.floor((py + (sy + .5) / ss - oy) / S))
                    text = 0 <= v < 7 and 0 <= u < 5 and g[v, u]
                    shadow = 0 <= v - 1 < 7 and 0 <= u - 1 < 5 and g[v - 1, u - 1]
                    acc += 1 if text else (-0.6 if shadow else 0)
            t[py, px] = acc / ss / ss
    return t


def template(k, ox, oy):
    key = (k, round(ox, 2), round(oy, 2))
    if key not in _cache:
        t = render(GB[k], ox, oy)
        t = t - t.mean()
        _cache[key] = t / np.linalg.norm(t)
    return _cache[key]


def patch(L, x, y):
    xi, yi = int(np.floor(x)) - 1, int(np.floor(y)) - 1
    c = L[yi:yi + H, xi:xi + W]
    c = c - c.mean()
    return c / (np.linalg.norm(c) + 1e-6), x - (xi + 1) + 1, y - (yi + 1) + 1


def classify(L, x, y, keys):
    c, ox, oy = patch(L, x, y)
    score = {k: max((c * template(k, ox + dx, oy + dy)).sum() for dx in (-0.4, 0, 0.4) for dy in (-0.4, 0, 0.4))
             for k in keys}
    ks = sorted(score, key=score.get, reverse=True)
    return ks[0], score[ks[0]], score[ks[0]] - score[ks[1]]


def read_number(L, y):
    best = None
    for sign in (0, 1):
        for n in (1, 2, 3, 4):
            x, s, tot, margin, cnt = X0, '', 0.0, 9.0, 0
            if sign:
                k, sc, m = classify(L, x, y, ['-'] + DIGITS)
                if k != '-':
                    sc -= 1
                s += '-'; tot += sc; cnt += 1; x += 6 * S
            for i in range(n + 5):
                k, sc, m = classify(L, x, y, DIGITS)
                s += k; tot += sc; cnt += 1; margin = min(margin, m)
                x += 6 * S
                if i == n - 1:
                    s += '.'; x += 2 * S
            if best is None or tot / cnt > best[1]:
                best = (s, tot / cnt, margin)
    return best


def frames(video, first, end, w=300, h=100):
    cmd = ['ffmpeg', '-loglevel', 'error', '-i', video, '-vf',
           f'trim=start_frame={first}:end_frame={end},crop={w}:{h}:0:0', '-vsync', '0',
           '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-']
    raw = subprocess.run(cmd, capture_output=True, check=True).stdout
    return np.frombuffer(raw, np.uint8).reshape(-1, h, w, 3)


def luma(f):
    f = f.astype(float)
    return 0.299 * f[:, :, 0] + 0.587 * f[:, :, 1] + 0.114 * f[:, :, 2]


def main():
    video, first, end, out = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
    rows = []
    for i, f in enumerate(frames(video, first, end)):
        L = luma(f)
        r = [read_number(L, y) for y in YS]
        rows.append([first + i] + [float(x[0]) for x in r] + [round(min(x[2] for x in r), 3)])
        print(first + i, *[x[0] for x in r], 'margin=%.2f' % min(x[2] for x in r), flush=True)
    json.dump(rows, open(out, 'w'))


if __name__ == '__main__':
    main()
