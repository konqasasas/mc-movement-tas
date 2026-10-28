#!/usr/bin/env python3
"""Random parkour courses that look more like real maps than problems/v1-*.

The v1 courses are a single file of pads going down and away. Real maps (human6) wind around, climb,
use ladders, jump around walls (neo), hit their heads under low ceilings, step up slabs and stairs,
and are full of blocks that are not part of the route. This generator builds such courses so that a
search can be developed on them without looking at the report-only human6 set.

    python3 tools/towergen/towergen.py <out-dir> <set-name> <first-seed> <count> [--segments N] [--decor N] [--floor]

Course i uses seed first-seed + i. Each course: a spawn pad, the start box on the pad's last block,
then N segments (default 24) along a path that turns every few segments, and a goal on the last pad.
Segment kinds: jump (gap 1-4, height -1..+1, varied pad blocks), ladder (climb a wall 3-5 high), neo
(jump around the end of a wall), head hitter (a ceiling two blocks above a pad before a jump), steps
(slab and stairs up). Pillars, glass panes and walls are scattered beside the route. Every placement
keeps the route's air space clear. Reachability is likely but not guaranteed (as in CourseGen).
--decor N scatters N more blocks and short pillars over the whole area; --floor lays a grass floor four
blocks under the lowest block, as many maps have (falling onto it is not the void).
"""
import json
import os
import random
import sys

PADS = [
    ("minecraft:stone", 8), ("minecraft:stone_slab[half=bottom]", 2), ("minecraft:stone_slab[half=top]", 1),
    ("minecraft:fence", 1), ("minecraft:cobblestone_wall", 1), ("minecraft:glass_pane", 1),
    ("minecraft:iron_bars", 1), ("minecraft:sea_lantern", 1), ("minecraft:wool", 2), ("minecraft:quartz_block", 2),
    ("minecraft:trapdoor[facing=north,half=top,open=false]", 1), ("minecraft:anvil[facing=north]", 1),
]
# Height of each pad block's top above its block y.
TOP = {"minecraft:stone_slab[half=bottom]": 0.5, "minecraft:fence": 1.5, "minecraft:cobblestone_wall": 1.5}
DECOR = ["minecraft:stone", "minecraft:glass", "minecraft:stained_hardened_clay", "minecraft:glass_pane",
         "minecraft:cobblestone_wall", "minecraft:quartz_block"]
DIRS = [(0, 1), (1, 0), (0, -1), (-1, 0)]


def pick(r, table):
    total = sum(w for _, w in table)
    k = r.randrange(total)
    for b, w in table:
        k -= w
        if k < 0:
            return b
    raise AssertionError


class Course:
    def __init__(self, r):
        self.r = r
        self.blocks = {}      # (x, y, z) -> block
        self.reserved = set()  # air the route needs

    def free(self, cells):
        return all(c not in self.blocks and c not in self.reserved for c in cells)

    def put(self, x, y, z, b):
        self.blocks[(x, y, z)] = b

    def reserve_above(self, x, y, z, h=3):
        for dy in range(1, h + 1):
            self.reserved.add((x, y + dy, z))


def top_of(b, y):
    return y + TOP.get(b, 1.0)


def generate(seed, segments, floor=False, decor=0):
    r = random.Random(seed)
    c = Course(r)
    # Spawn pad: x -1..1, z -6..0, y 64; the start box is the last row.
    for x in range(-1, 2):
        for z in range(-6, 1):
            c.put(x, 64, z, "minecraft:stone")
            c.reserve_above(x, 64, z)
    cur = (0, 64, 0)
    cur_top = 65.0
    d = 0
    kinds = []
    since_turn = 0
    done = 0
    attempts = 0
    while done < segments and attempts < 5000:
        attempts += 1
        if since_turn >= r.randint(3, 5):
            d = (d + r.choice([1, 3])) % 4
            since_turn = 0
        dx, dz = DIRS[d]
        sx, sz = -dz, dx  # to the side
        kind = pick(r, [("jump", 10), ("ladder", 2), ("neo", 2), ("head", 2), ("steps", 2)])
        x, y, z = cur
        if kind == "jump":
            dy = r.choice([-1, 0, 0, 1, 1])
            block = pick(r, PADS)
            rise = top_of(block, y + dy) - cur_top
            if rise > 1.0:
                continue
            gap = r.randint(1, 2 if rise > 0.6 else 3 if rise > -0.6 else 4)
            off = r.randint(-1, 1)
            n = (x + dx * (gap + 1) + sx * off, y + dy, z + dz * (gap + 1) + sz * off)
            path = [(x + dx * i + sx * (off if i > gap // 2 else 0), y + k, z + dz * i + sz * (off if i > gap // 2 else 0))
                    for i in range(1, gap + 1) for k in range(1, 4)]
            if not c.free([n] + [(n[0], n[1] + k, n[2]) for k in range(1, 3)] + path):
                continue
            c.put(*n, block)
            c.reserve_above(*n)
            for p in path:
                c.reserved.add(p)
            cur, cur_top = n, top_of(block, n[1])
        elif kind == "ladder":
            h = r.randint(3, 5)
            lad = (x + dx, y, z + dz)
            wall = (x + 2 * dx, y, z + 2 * dz)
            cells = [(lad[0], y + k, lad[2]) for k in range(1, h + 1)] + [(wall[0], y + k, wall[2]) for k in range(1, h + 2)]
            cells += [(wall[0], y + h + 1 + k, wall[2]) for k in range(1, 3)]
            if cur_top != y + 1 or not c.free(cells):
                continue
            facing = {(0, 1): "north", (1, 0): "west", (0, -1): "south", (-1, 0): "east"}[(dx, dz)]
            c.put(lad[0], y, lad[2], "minecraft:stone")
            for k in range(1, h + 1):
                c.put(lad[0], y + k, lad[2], "minecraft:ladder[facing=%s]" % facing)
                c.put(wall[0], y + k, wall[2], "minecraft:stone")
            c.put(wall[0], y + h + 1, wall[2], "minecraft:stone")
            c.reserve_above(wall[0], y + h + 1, wall[2])
            c.reserve_above(lad[0], y + h, lad[2], 3)
            cur, cur_top = (wall[0], y + h + 1, wall[2]), y + h + 2.0
        elif kind == "neo":
            a = (x + dx, y + 1, z + dz)
            wall = [(a[0], y + k, a[2]) for k in range(1, 4)] + [(a[0] - sx, y + k, a[2] - sz) for k in range(1, 4)]
            n = (x + 2 * dx + sx, y, z + 2 * dz + sz)
            around = [(x + sx, y + k, z + sz) for k in range(1, 3)] + [(x + dx + sx, y + k, z + dz + sz) for k in range(1, 3)]
            if cur_top != y + 1 or not c.free(wall + [n] + around + [(n[0], n[1] + k, n[2]) for k in range(1, 3)]):
                continue
            for w in wall:
                c.put(*w, "minecraft:stained_hardened_clay")
            c.put(*n, "minecraft:stone")
            c.reserve_above(*n)
            for p in around:
                c.reserved.add(p)
            cur, cur_top = n, y + 1.0
        elif kind == "head":
            ceil = (x, y + 3, z)
            gap = r.randint(1, 3)
            n = (x + dx * (gap + 1), y, z + dz * (gap + 1))
            path = [(x + dx * i, y + k, z + dz * i) for i in range(1, gap + 1) for k in range(1, 3)]
            if cur_top != y + 1 or (ceil in c.reserved and ceil not in [(x, y + 3, z)]) or not c.free([n] + path):
                continue
            c.reserved.discard(ceil)
            if ceil in c.blocks:
                continue
            c.put(*ceil, "minecraft:stone")
            c.put(*n, "minecraft:stone")
            c.reserve_above(*n)
            for p in path:
                c.reserved.add(p)
            cur, cur_top = n, y + 1.0
        elif kind == "steps":
            s1 = (x + dx, y + 1, z + dz)
            s2 = (x + 2 * dx, y + 1, z + 2 * dz)
            cells = [s1, s2] + [(s1[0], y + 1 + k, s1[2]) for k in range(1, 3)] + [(s2[0], y + 1 + k, s2[2]) for k in range(1, 3)]
            if cur_top != y + 1 or not c.free(cells):
                continue
            facing = {(0, 1): "south", (1, 0): "east", (0, -1): "north", (-1, 0): "west"}[(dx, dz)]
            c.put(*s1, "minecraft:stone_slab[half=bottom]" if r.random() < 0.5 else
                  "minecraft:stone_stairs[facing=%s,half=bottom]" % {"south": "north", "east": "west", "north": "south", "west": "east"}[facing])
            c.put(*s2, "minecraft:stone")
            c.reserve_above(*s1)
            c.reserve_above(*s2)
            cur, cur_top = s2, y + 2.0
        kinds.append(kind)
        since_turn += 1
        done += 1
        # Scenery beside the route.
        if r.random() < 0.6:
            px, pz = cur[0] + sx * r.choice([-3, -2, 2, 3]), cur[2] + sz * r.choice([-3, -2, 2, 3])
            b = r.choice(DECOR)
            for k in range(r.randint(1, 4)):
                if c.free([(px, cur[1] + k, pz)]):
                    c.put(px, cur[1] + k, pz, b)
    # Clutter like a real map: blocks and pillars all over the course's area, off the route's air.
    xs = [x for (x, _, _) in c.blocks]
    zs = [z for (_, _, z) in c.blocks]
    ys0 = [y for (_, y, _) in c.blocks]
    for _ in range(decor):
        px, pz = r.randint(min(xs) - 4, max(xs) + 4), r.randint(min(zs) - 4, max(zs) + 4)
        py = r.randint(min(ys0) - 2, max(ys0) + 2)
        b = r.choice(DECOR + ["minecraft:stone_slab[half=bottom]", "minecraft:fence", "minecraft:wool"])
        for k in range(r.choice([1, 1, 1, 2, 3])):
            if c.free([(px, py + k, pz)]) and c.free([(px, py + k + 1, pz)]):
                c.put(px, py + k, pz, b)
    if floor:
        fy = min(ys0) - 4
        for x in range(min(xs) - 6, max(xs) + 7):
            for z in range(min(zs) - 6, max(zs) + 7):
                c.put(x, fy, z, "minecraft:grass")
    goal_top = cur_top
    gx, gy, gz = cur
    fills = [{"from": [x, y, z], "to": [x, y, z], "block": b} for (x, y, z), b in sorted(c.blocks.items())]
    ys = [y for (_, y, _) in c.blocks]
    return {
        "format": "mctas-problem", "version": 1,
        "title": "%d segments, generated tower course (seed %d): %s" % (segments, seed, ", ".join(kinds)),
        "mcVersion": "1.12.2",
        "world": {"source": "inline", "fills": fills},
        "spawn": {"pos": [0.5, 65.0, -5.5], "yaw": 0.0, "pitch": 0.0},
        "start": {"min": [-1.0, 65.0, 0.0], "max": [2.0, 66.0, 1.0]},
        "goal": {"min": [gx - 0.3, goal_top, gz - 0.3], "max": [gx + 1.3, goal_top, gz + 1.3]},
        "limits": {"maxTicks": 1200 + 40 * segments, "voidY": min(ys) - 2.0},
    }, kinds


def main():
    args = sys.argv[1:]
    segments, decor, floor = 24, 0, False
    if "--segments" in args:
        i = args.index("--segments")
        segments = int(args[i + 1])
        del args[i:i + 2]
    if "--decor" in args:
        i = args.index("--decor")
        decor = int(args[i + 1])
        del args[i:i + 2]
    if "--floor" in args:
        args.remove("--floor")
        floor = True
    out, name, first, count = args[0], args[1], int(args[2]), int(args[3])
    os.makedirs(out, exist_ok=True)
    for seed in range(first, first + count):
        p, kinds = generate(seed, segments, floor, decor)
        base = "t%04d" % seed
        p["id"] = name + "/" + base
        p = {k: p[k] for k in ["format", "version", "id", "title", "mcVersion", "world", "spawn", "start", "goal", "limits"]}
        with open(os.path.join(out, base + ".problem.json"), "w") as f:
            json.dump(p, f, indent=1)
        print("wrote", os.path.join(out, base + ".problem.json"), len(kinds), "segments")


if __name__ == "__main__":
    main()
