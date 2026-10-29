#!/usr/bin/env python3
"""Long random dev courses made of the element types the planner stalls on (F-513), packed densely.

    python3 tools/devgen/devgen.py <out-dir> <set-name> <first-seed> <count> [--min-length N] [--max-length N] [--only k1,k2] [--hard]

Course i uses seed first-seed + i and runs along a winding path until it is between --min-length and
--max-length blocks long (default 100 and 400; the length is the path's horizontal length). Every
module starts and ends on a full block, and its moves stay inside what a sprint jump does with room to
spare, except where noted, so that a human can get through (the planner is not asked):

- slabs: two to four slabs, each half a block higher, one or two blocks apart
- stairs: two to four stairs up, touching or one block apart
- skulls: two to four skulls on pillars, level or one up, two or three apart
- posts: three to five fence, wall, glass pane or iron bar posts (1.5 high), two or three apart, zigzagging
- slalom: a corridor three wide between barriers with rows of panes, bars and walls, a gap at alternate ends
- passage: a corridor one block wide between fences, walls or bars
- ladder: a ladder up a wall of stone or barrier, three to six high (as high as the wall)
- ladder jump: a jump onto a single ladder hung over nothing, one or two blocks out and two up (two
  out needs a jump on the last tick at the edge and a sneak on catching it: tight, like realdev/exp2)
- zigzag: single ladders alternating between two columns up a wall four high
- barrier: jumps between barrier blocks (invisible full blocks), up to one up or down
- drop: a drop of two to four onto a pad

--hard (gen-dev2) packs the elements tighter and lays them over each other: jumps closer to what a
sprint jump just makes with momentum (posts four apart, barrier jumps up one over two blocks, skulls
three apart; the first jump of a module, made without a run-up, a block shorter), more posts and skulls per module, more scenery, and four modules of its own:

- thicket: a corridor five wide filled with thin posts two high, with a winding way through and slabs on
  the floor (like the dense panes, walls and bars where poseidon stalls)
- skullspiral: five to eight skulls on pillars, each one up, turning (gaining height on small footholds)
- stairtower: stairs and barrier steps up between fences, then a ladder (stairs, barriers and ladders)
- postladder: posts, then a jump from the last post onto a single ladder

Scenery (pillars, panes, walls, slabs) is scattered beside the route; every placement keeps the route's
air clear. The pads between modules are full blocks of varied kinds.
"""
import json
import os
import random
import sys

PADS = ["minecraft:stone", "minecraft:quartz_block", "minecraft:wool", "minecraft:concrete", "minecraft:stained_hardened_clay",
        "minecraft:sea_lantern", "minecraft:prismarine", "minecraft:stonebrick"]
THIN = ["minecraft:spruce_fence", "minecraft:cobblestone_wall", "minecraft:stained_glass_pane", "minecraft:iron_bars"]
SCENERY = ["minecraft:stone", "minecraft:glass", "minecraft:stained_glass_pane", "minecraft:cobblestone_wall",
           "minecraft:iron_bars", "minecraft:spruce_fence", "minecraft:stone_slab[half=bottom]", "minecraft:quartz_block"]
DIRS = [(0, 1), (1, 0), (0, -1), (-1, 0)]
NAME = {(0, 1): "south", (1, 0): "east", (0, -1): "north", (-1, 0): "west"}
MODULES = [("slabs", 3), ("stairs", 3), ("skulls", 3), ("posts", 3), ("slalom", 2), ("passage", 2), ("ladder", 2),
           ("ladderjump", 2), ("zigzag", 2), ("barrier", 2), ("drop", 1)]
HARD_MODULES = [("slabs", 2), ("stairs", 1), ("skulls", 3), ("posts", 4), ("slalom", 2), ("passage", 1), ("ladder", 1),
                ("ladderjump", 2), ("zigzag", 2), ("barrier", 3), ("drop", 1),
                ("thicket", 4), ("skullspiral", 3), ("stairtower", 3), ("postladder", 3)]
HARD = False


def pick(r, table):
    total = sum(w for _, w in table)
    k = r.randrange(total)
    for b, w in table:
        k -= w
        if k < 0:
            return b
    raise AssertionError


class Module:
    """Blocks and route air in the frame of the pad the player stands on: f forward, l to the left, u up."""

    def __init__(self, x, y, z, d):
        self.x, self.y, self.z = x, y, z
        self.dx, self.dz = DIRS[d]
        self.sx, self.sz = -self.dz, self.dx
        self.blocks = {}
        self.air = set()
        self.exit = None

    def at(self, f, l, u):
        return (self.x + self.dx * f + self.sx * l, self.y + u, self.z + self.dz * f + self.sz * l)

    def put(self, f, l, u, b):
        self.blocks[self.at(f, l, u)] = b

    def clear(self, f, l, u, h=1):
        for k in range(h):
            self.air.add(self.at(f, l, u + k))

    def column(self, f, l, u0, u1, b):
        for u in range(u0, u1 + 1):
            self.put(f, l, u, b)

    def pad(self, f, l, u, b):
        """A full block to land on, with room above, and the module's exit."""
        self.put(f, l, u, b)
        self.clear(f, l, u + 1, 3)
        self.exit = (f, l, u)

    def lane(self, f0, f1, l, u, h=3):
        """Air for the route from f0 to f1 at lateral l, from height u up."""
        for f in range(f0, f1 + 1):
            self.clear(f, l, u, h)

    @property
    def forward(self):
        return NAME[(self.dx, self.dz)]

    @property
    def backward(self):
        return NAME[(-self.dx, -self.dz)]


def build(kind, r, m):
    """Fills m with one module after the pad at (0, 0, 0); the player stands at height 1 on it."""
    if kind == "slabs":
        k, f, u, top = r.randint(2, 4), 0, 0, 1.0
        for _ in range(k):
            f += r.randint(2, 3)
            top += 0.5
            # A bottom slab at u + 1 tops out at u + 1.5, a top slab at u + 1 at u + 2.
            if top == int(top):
                m.put(f, 0, int(top) - 1, "minecraft:stone_slab[half=top]")
            else:
                m.put(f, 0, int(top), "minecraft:stone_slab[half=bottom]")
            m.lane(f - 2, f, 0, int(top) + (0 if top == int(top) else 1), 3)
        # The pad is a full block at floor(top): half a block up from a bottom slab (two or three
        # out), one up from a top slab (two out).
        f += 2 if top == int(top) else r.randint(2, 3)
        pu = int(top)
        m.lane(f - 2, f, 0, pu + 1)
        m.pad(f, 0, pu, pick_pad(r))
    elif kind == "stairs":
        k, f, u = r.randint(2, 4), 0, 0
        for _ in range(k):
            f += r.randint(1, 2)
            u += 1
            m.put(f, 0, u, "minecraft:stone_brick_stairs[facing=%s,half=bottom]" % m.forward)
            m.column(f, 0, 0, u - 1, "minecraft:stone")
            m.lane(f - 1, f, 0, u + 1)
        f += r.randint(1, 2)
        m.column(f, 0, 0, u, pick_pad(r))
        m.lane(f - 1, f, 0, u + 2)
        m.pad(f, 0, u + 1, pick_pad(r))
    elif kind == "skulls":
        k, f, u, l = r.randint(4, 7) if HARD else r.randint(2, 4), 0, 1, 0
        # The first skull is half a block above the pad; then level (two or three apart, three or four
        # with --hard) or one up (two apart).
        for i in range(k):
            up = 0 if i == 0 else r.choice([0, 0, 1])
            u += up
            f += 2 if up else r.randint(3, 4) if HARD and i else r.randint(2, 3)
            l = max(-1, min(1, l + r.choice([-1, 0, 0, 1])))
            m.column(f, l, 0, u - 1, "minecraft:stone")
            m.put(f, l, u, "minecraft:skull[facing=up]")
            # The jump up to a skull passes the column before it at the skull's own height.
            m.lane(f - 4, f, l, u, 4) if HARD else m.lane(f - 2, f, l, u + 1, 3)
        f += 2
        m.column(f, l, 0, u - 1, "minecraft:stone")
        m.lane(f - 2, f, l, u + 1)
        m.pad(f, l, u, pick_pad(r))
    elif kind == "posts":
        k, f, l = r.randint(4, 7) if HARD else r.randint(3, 5), 0, 0
        post = r.choice(THIN)
        # Posts level with the pad's block: their tops are 1.5 above its bottom, half a block up.
        for i in range(k):
            if HARD:
                # Four ahead straight, or three ahead and one across (one less for the first post, which
                # is jumped to without a run-up); the kind changes from post to post.
                dl = r.choice([-1, 0, 1])
                f += (4 if dl == 0 else 3) - (1 if i == 0 else 0)
                post = r.choice(THIN)
            else:
                f += r.randint(2, 3)
                dl = r.choice([-1, 1])
            l = max(-1, min(1, l + dl))
            m.put(f, l, -1, "minecraft:stone")
            m.put(f, l, 0, post)
            m.lane(f - (4 if HARD else 2), f, l, 1, 3)
        f += 2
        m.lane(f - 2, f, l, 2)
        m.put(f, l, 0, "minecraft:stone")
        m.pad(f, l, 1, pick_pad(r))
    elif kind == "slalom":
        rows = r.randint(3, 5)
        thin = r.choice(THIN)
        length = 2 * rows + 2
        for f in range(1, length + 1):
            for l in range(-1, 2):
                m.put(f, l, 0, "minecraft:stone")
            m.column(f, -2, 1, 2, "minecraft:barrier")
            m.column(f, 2, 1, 2, "minecraft:barrier")
        gap = r.choice([-1, 1])
        for i in range(rows):
            f = 2 + 2 * i
            for l in range(-1, 2):
                if l != gap:
                    m.column(f, l, 1, 2, thin)
                else:
                    m.clear(f, l, 1, 2)
            gap = -gap
        m.pad(length + 1, 0, 0, pick_pad(r))
        for f in range(1, length + 1):
            if (f - 2) % 2 or f < 2 or f >= 2 + 2 * rows:
                for l in range(-1, 2):
                    m.clear(f, l, 1, 2)
    elif kind == "passage":
        length = r.randint(3, 6)
        wall = r.choice(THIN)
        for f in range(1, length + 1):
            m.put(f, 0, 0, "minecraft:stone")
            m.column(f, -1, 1, 2, wall)
            m.column(f, 1, 1, 2, wall)
            m.clear(f, 0, 1, 3)
        m.pad(length + 1, 0, 0, pick_pad(r))
    elif kind == "ladder":
        # The ladder reaches as high as the wall, so the feet rise above the wall's top before leaving it.
        h = r.randint(3, 6)
        wall = r.choice(["minecraft:stone", "minecraft:barrier"])
        m.put(1, 0, 0, "minecraft:stone")
        for u in range(1, h + 1):
            m.put(1, 0, u, "minecraft:ladder[facing=%s]" % m.backward)
            m.put(2, 0, u, wall)
        m.clear(1, 0, h + 1, 3)
        m.clear(2, 0, h + 1, 3)
        m.exit = (2, 0, h)
    elif kind == "ladderjump":
        # From the pad's edge over one or two blocks of air onto a ladder two up, the block behind it a step.
        out = 2 if r.random() < 0.3 else 1
        f = 1 + out
        m.lane(1, f, 0, 1, 3)
        m.put(f, 0, 2, "minecraft:ladder[facing=%s]" % m.backward)
        m.clear(f, 0, 3, 2)
        m.put(f + 1, 0, 2, "minecraft:stone")
        m.clear(f + 1, 0, 3, 3)
        m.exit = (f + 1, 0, 2)
    elif kind == "zigzag":
        # Ladders on a wall four high, alternating between the column ahead and the one beside it.
        side = r.choice([-1, 1])
        m.put(1, 0, 0, "minecraft:stone")
        m.put(1, side, 0, "minecraft:stone")
        for u in range(1, 5):
            m.put(2, 0, u, "minecraft:stone")
            m.put(2, side, u, "minecraft:stone")
            l = 0 if u % 2 else side
            m.put(1, l, u, "minecraft:ladder[facing=%s]" % m.backward)
            m.clear(1, side - l, u, 1)
        m.clear(1, 0, 5, 3)
        m.clear(1, side, 5, 3)
        m.clear(2, 0, 5, 3)
        m.exit = (2, 0, 4)
    elif kind == "barrier":
        k, f, u, l = r.randint(2, 4), 0, 0, 0
        for i in range(k):
            du = r.choice([-1, 0, 0, 1])
            if HARD and i:
                gap = 2 if du > 0 else 3 if du == 0 else r.randint(3, 4)
            else:
                gap = r.randint(1, 2) if du > 0 else r.randint(1, 3)
            f += gap + 1
            u += du
            if gap < 3:
                l = max(-1, min(1, l + r.choice([-1, 0, 1])))
            # From the lower of the two feet heights, and a block more for a jump up (F-528).
            m.lane(f - gap, f - 1, l, min(u, u - du) + 1, 4 if du > 0 else 3)
            m.put(f, l, u, "minecraft:barrier")
            m.clear(f, l, u + 1, 3)
        f += 2
        m.lane(f - 1, f - 1, l, u + 1)
        m.pad(f, l, u, pick_pad(r))
    elif kind == "thicket":
        # Five wide between barriers, posts two high everywhere but a winding way (each step forward or
        # across, never both, so that connected panes leave no diagonal squeeze).
        length = r.randint(8, 14)
        thin = r.choice(THIN)
        way, l = set(), 0
        way.add((1, 0))
        for f in range(1, length + 1):
            way.add((f, l))
            if f < length and r.random() < 0.5:
                nl = max(-2, min(2, l + r.choice([-1, 1])))
                for x in range(min(l, nl), max(l, nl) + 1):
                    way.add((f, x))
                l = nl
        floor = 0
        for f in range(1, length + 1):
            for x in range(-2, 3):
                m.put(f, x, 0, "minecraft:stone")
            m.column(f, -3, 1, 2, "minecraft:barrier")
            m.column(f, 3, 1, 2, "minecraft:barrier")
            for x in range(-2, 3):
                if (f, x) in way:
                    if r.random() < 0.15:
                        m.put(f, x, 1, "minecraft:stone_slab[half=bottom]")
                        m.clear(f, x, 2, 3)
                    else:
                        m.clear(f, x, 1, 3)
                elif r.random() < 0.7:
                    m.column(f, x, 1, 2, r.choice(THIN) if r.random() < 0.3 else thin)
        m.put(length + 1, l, 0, "minecraft:stone")
        m.pad(length + 1, l, 0, pick_pad(r))
    elif kind == "skullspiral":
        # Each skull one up and two from the last, the heading switching between ahead and one side
        # every two skulls (a staircase of turns).
        k = r.randint(5, 8)
        f, l, u = 0, 0, 0
        side = r.choice([-1, 1])
        for i in range(k):
            df, dl = (1, 0) if (i // 2) % 2 == 0 else (0, side)
            f, l, u = f + 2 * df, l + 2 * dl, u + 1
            m.column(f, l, 0, u - 1, "minecraft:stone")
            m.put(f, l, u, "minecraft:skull[facing=up]")
            m.clear(f, l, u + 1, 3)
            m.clear(f - df, l - dl, u, 4)
        df, dl = (1, 0) if (k // 2) % 2 == 0 else (0, side)
        f, l = f + 2 * df, l + 2 * dl
        m.column(f, l, 0, u - 1, "minecraft:stone")
        m.clear(f - df, l - dl, u, 4)
        m.pad(f, l, u, pick_pad(r))
    elif kind == "stairtower":
        # Stairs and barrier steps up, fences on both sides, then a ladder up the wall at the top.
        k, f, u = r.randint(3, 5), 0, 0
        fence = r.choice(THIN)
        for _ in range(k):
            f += r.randint(1, 2)
            u += 1
            if r.random() < 0.5:
                m.put(f, 0, u, "minecraft:stone_brick_stairs[facing=%s,half=bottom]" % m.forward)
            else:
                m.put(f, 0, u, "minecraft:barrier")
            m.column(f, 0, 0, u - 1, "minecraft:stone")
            m.column(f, -1, u + 1, u + 1, fence)
            m.column(f, 1, u + 1, u + 1, fence)
            m.lane(f - 2, f, 0, u + 1)
        f += 1
        m.column(f, 0, 0, u, "minecraft:stone")
        m.lane(f - 1, f, 0, u + 1)
        h = r.randint(3, 5)
        for x in range(1, h + 1):
            m.put(f + 1, 0, u + x, "minecraft:ladder[facing=%s]" % m.backward)
            m.put(f + 2, 0, u + x, "minecraft:stone")
        m.column(f + 1, 0, 0, u, "minecraft:stone")
        m.clear(f + 1, 0, u + h + 1, 3)
        m.clear(f + 2, 0, u + h + 1, 3)
        m.exit = (f + 2, 0, u + h)
    elif kind == "postladder":
        # Posts three apart, then from the last post one block out onto a single ladder one up.
        k, f = r.randint(2, 3), 0
        for _ in range(k):
            f += 3
            m.put(f, 0, -1, "minecraft:stone")
            m.put(f, 0, 0, r.choice(THIN))
            m.lane(f - 3, f, 0, 1, 3)
        m.lane(f + 1, f + 2, 0, 1, 3)
        m.put(f + 2, 0, 2, "minecraft:ladder[facing=%s]" % m.backward)
        m.clear(f + 2, 0, 3, 2)
        m.put(f + 3, 0, 2, "minecraft:stone")
        m.clear(f + 3, 0, 3, 3)
        m.exit = (f + 3, 0, 2)
    elif kind == "drop":
        down = r.randint(2, 4)
        gap = r.randint(1, 3)
        m.lane(1, gap, 0, 1 - down, 3 + down)
        m.pad(gap + 1, 0, -down, pick_pad(r))
    else:
        raise ValueError(kind)


def pick_pad(r):
    return r.choice(PADS)


def generate(seed, min_length, max_length, only=None):
    r = random.Random(seed)
    modules = [(k, w) for k, w in (HARD_MODULES if HARD else MODULES) if only is None or k in only]
    blocks, air = {}, set()
    for x in range(-1, 2):
        for z in range(-6, 1):
            blocks[(x, 64, z)] = "minecraft:stone"
            for k in range(1, 4):
                air.add((x, 64 + k, z))
    cur, d, length = (0, 64, 0), 0, 0.0
    target = r.randint(min_length, max_length)
    kinds = []
    since_turn, fails = 0, 0
    while length < target and fails < 4000:
        nd = d
        if since_turn >= r.randint(2, 4):
            nd = (d + r.choice([1, 3])) % 4
        kind = pick(r, modules)
        m = Module(cur[0], cur[1], cur[2], nd)
        build(kind, r, m)
        cells = set(m.blocks)
        if any(c in blocks or c in air for c in cells) or any(c in blocks for c in m.air):
            fails += 1
            continue
        # Keep the course above the void and below the build limit.
        ex = m.at(*m.exit)
        if ex[1] < 20 or ex[1] > 240:
            fails += 1
            continue
        blocks.update(m.blocks)
        air |= m.air
        length += abs(ex[0] - cur[0]) + abs(ex[2] - cur[2])
        since_turn = 0 if nd != d else since_turn + 1
        if os.environ.get("DEVGEN_TRACE"):
            print("  %-12s from %s to %s heading %s" % (kind, cur, ex, NAME[DIRS[nd]]), file=sys.stderr)
        cur, d = ex, nd
        kinds.append(kind)
        # Scenery beside the module.
        for _ in range(r.randint(3, 8) if HARD else r.randint(0, 3)):
            f, l = r.randint(0, 4), r.choice([-3, -2, 2, 3])
            b = r.choice(SCENERY)
            for k in range(r.randint(1, 3)):
                c = m.at(f, l, k)
                if c not in blocks and c not in air:
                    blocks[c] = b
    gx, gy, gz = cur
    ys = [y for (_, y, _) in blocks]
    fills = [{"from": [x, y, z], "block": b} for (x, y, z), b in sorted(blocks.items())]
    return {
        "format": "mctas-problem", "version": 1,
        "title": "generated dev course (seed %d), %d blocks: %s" % (seed, length, ", ".join(kinds)),
        "mcVersion": "1.12.2",
        "world": {"source": "inline", "fills": fills},
        "spawn": {"pos": [0.5, 65.0, -5.5], "yaw": 0.0, "pitch": 0.0},
        "start": {"min": [-1.0, 65.0, 0.0], "max": [2.0, 66.0, 1.0]},
        "goal": {"min": [gx - 0.3, gy + 1.0, gz - 0.3], "max": [gx + 1.3, gy + 1.0, gz + 1.3]},
        "limits": {"maxTicks": int(400 + 12 * length), "voidY": min(ys) - 2.0},
    }, kinds, length


def main():
    args = sys.argv[1:]
    lo, hi = 100, 400
    if "--min-length" in args:
        i = args.index("--min-length")
        lo = int(args[i + 1])
        del args[i:i + 2]
    if "--max-length" in args:
        i = args.index("--max-length")
        hi = int(args[i + 1])
        del args[i:i + 2]
    global HARD
    if "--hard" in args:
        args.remove("--hard")
        HARD = True
    only = None
    if "--only" in args:
        i = args.index("--only")
        only = args[i + 1].split(",")
        del args[i:i + 2]
    out, name, first, count = args[0], args[1], int(args[2]), int(args[3])
    os.makedirs(out, exist_ok=True)
    for seed in range(first, first + count):
        p, kinds, length = generate(seed, lo, hi, only)
        base = "g%04d" % seed
        p["id"] = name + "/" + base
        p = {k: p[k] for k in ["format", "version", "id", "title", "mcVersion", "world", "spawn", "start", "goal", "limits"]}
        with open(os.path.join(out, base + ".problem.json"), "w") as f:
            json.dump(p, f, indent=1)
        print("wrote", os.path.join(out, base + ".problem.json"), int(length), "blocks,", len(kinds), "modules")


if __name__ == "__main__":
    main()
