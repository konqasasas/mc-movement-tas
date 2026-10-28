"""Read the coordinate HUD and tick counter of a recorded Minecraft run, frame by frame.

The HUD draws X, Y, Z with 8 decimals and a world tick counter in the vanilla font. Each glyph is
matched against the font bitmap rendered with its drop shadow, on the luma channel (the chroma of a
4:2:0 video is half resolution). Prints one line per frame and writes a JSON map tick -> [x, y, z].

usage: python3 hud_ocr.py VIDEO FIRST_FRAME END_FRAME OUT.json
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
}
GB = {k: np.array([[c == '#' for c in row] for row in v.split()]) for k, v in GLYPHS.items()}

# Geometry of the HUD in the 1080p video: font pixel size, left edge of the numbers, tops of the X, Y
# and Z lines, left edge of the tick counter digits, top of the tick counter line.
S, X0, YS, TX0, TY = 2.25, 31.5, (182.5, 205.0, 227.0), 77.0, 317.0
W, H = 13, 18
OFFSETS = [(ox, oy) for ox in (0.5, 1.0, 1.5, 2.0) for oy in (0.5, 1.0, 1.5, 2.0)]
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
                    acc += 1 if text else (-1 if shadow else 0)
            t[py, px] = acc / ss / ss
    return t


def template(k, o):
    if (k, o) not in _cache:
        t = render(GB[k], *o)
        t = t - t.mean()
        _cache[(k, o)] = t / np.linalg.norm(t)
    return _cache[(k, o)]


def classify(L, x, y):
    xi, yi = int(np.floor(x)) - 1, int(np.floor(y)) - 1
    c = L[yi:yi + H, xi:xi + W]
    c = c - c.mean()
    c = c / (np.linalg.norm(c) + 1e-6)
    score = {k: max((c * template(k, o)).sum() for o in OFFSETS) for k in GB}
    ks = sorted(score, key=score.get, reverse=True)
    return ks[0], score[ks[0]], score[ks[0]] - score[ks[1]]


def read_number(L, y):
    """Reads d.dddddddd or dd.dddddddd, whichever layout matches the glyphs better."""
    best = None
    for n in (1, 2):
        pos = [X0 + 6 * S * i for i in range(n)] + [X0 + 6 * S * n + 2 * S + 6 * S * i for i in range(8)]
        s, tot, margin = '', 0.0, 9.0
        for i, x in enumerate(pos):
            k, sc, m = classify(L, x, y)
            s += k
            tot += sc
            margin = min(margin, m)
            if i == n - 1:
                s += '.'
        if best is None or tot / len(pos) > best[1]:
            best = (s, tot / len(pos), margin)
    return best


def read_ticks(L, digits=5):
    return ''.join(classify(L, TX0 + 6 * S * j, TY)[0] for j in range(digits))


def frames(video, first, end, w=520, h=350):
    cmd = ['ffmpeg', '-loglevel', 'error', '-i', video, '-vf',
           f'trim=start_frame={first}:end_frame={end},crop={w}:{h}:0:0', '-vsync', '0',
           '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-']
    raw = subprocess.run(cmd, capture_output=True, check=True).stdout
    return np.frombuffer(raw, np.uint8).reshape(-1, h, w, 3)


def main():
    video, first, end, out = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
    obs, conflicts = {}, 0
    for i, f in enumerate(frames(video, first, end)):
        f = f.astype(float)
        L = 0.299 * f[:, :, 0] + 0.587 * f[:, :, 1] + 0.114 * f[:, :, 2]
        xyz = [read_number(L, y) for y in YS]
        t = int(read_ticks(L))
        v = [float(r[0]) for r in xyz]
        print(first + i, t, *[r[0] for r in xyz], 'margin=%.2f' % min(r[2] for r in xyz))
        if t in obs and obs[t] != v:
            conflicts += 1
            print('  conflict with an earlier frame of tick', t, obs[t], file=sys.stderr)
        obs.setdefault(t, v)
    ticks = sorted(obs)
    missing = [t for t in range(ticks[0], ticks[-1]) if t not in obs]
    print(f'ticks {ticks[0]}..{ticks[-1]}, missing {missing}, conflicts {conflicts}', file=sys.stderr)
    json.dump({str(t): obs[t] for t in ticks}, open(out, 'w'), indent=0)


if __name__ == '__main__':
    main()
