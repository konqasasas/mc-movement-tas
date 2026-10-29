#!/usr/bin/env python3
"""Dev courses with decoys (WS13): devgen's courses plus a module whose straight jump looks possible but is not.

    python3 tools/decoygen/decoygen.py <out-dir> <set-name> <first-seed> <count> [devgen options]

Takes the same options as tools/devgen/devgen.py (--hard, --only, --min-length, --max-length) and adds
the modules "decoy" (weight 2) and "deadend" (weight 4) to its table:

- decoy: the next pad is straight ahead but out of reach, a gap of five on the level or of four one up
  (a sprint jump with momentum makes four on the level and three one up), while the planner's optimistic
  graph still counts it a move; the way that works winds round to it on the side over three blocks.
  The planner's A* is drawn to the straight jump and spends its time there (the verdure and tohell type of
  stall) unless something like the learned guide tells it that jump is not made.
- deadend: blocks straight ahead, three apart and easy, lead to a gap of five before the exit; the way
  that works runs six to the side, too far from them to jump across, and comes back in at the exit.
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "devgen"))
import devgen  # noqa: E402

_build = devgen.build


def build(kind, r, m):
    if kind == "deadend":
        return deadend(r, m)
    if kind != "decoy":
        return _build(kind, r, m)
    s = r.choice([-1, 1])
    up = r.choice([0, 1])
    far = 6 if up == 0 else 5
    # The straight jump: only air between the pad and the exit.
    m.lane(1, far - 1, 0, 1, 3 + up)
    # The way round, on one side: three blocks, each a short level jump from the last.
    way = [(1, 3 * s), (4, 4 * s), (far, 2 * s)]
    for f, l in way:
        m.put(f, l, 0, devgen.pick_pad(r))
        m.clear(f, l, 1, 3)
    for (f0, l0), (f1, l1) in zip([(0, 0)] + way, way + [(far, 0)]):
        for k in range(1, 4):
            m.clear(f0 + (f1 - f0) * k // 4, l0 + (l1 - l0) * k // 4, 1, 3 + up)
    # The exit: one up needs a step from the last block of the way.
    if up:
        m.put(far, s, 0, devgen.pick_pad(r))
        m.clear(far, s, 1, 3)
    m.column(far, 0, -1, up - 1, "minecraft:stone")
    m.pad(far, 0, up, devgen.pick_pad(r))


def deadend(r, m):
    s = r.choice([-1, 1])
    # The lure: blocks straight ahead three apart, easy to run along, ending a gap of five short of the exit.
    for f in (3, 6, 9):
        m.put(f, 0, 0, devgen.pick_pad(r))
        m.clear(f, 0, 1, 3)
    m.lane(1, 14, 0, 1, 3)
    # The way that works, six to the side: too far from the lure to jump across.
    way = [(2, 3 * s), (5, 6 * s), (8, 6 * s), (11, 6 * s), (14, 4 * s), (15, 2 * s)]
    for f, l in way:
        m.put(f, l, 0, devgen.pick_pad(r))
        m.clear(f, l, 1, 3)
    for (f0, l0), (f1, l1) in zip([(0, 0)] + way, way + [(15, 0)]):
        for k in range(1, 4):
            m.clear(f0 + (f1 - f0) * k // 4, l0 + (l1 - l0) * k // 4, 1, 3)
    m.column(15, 0, -1, -1, "minecraft:stone")
    m.pad(15, 0, 0, devgen.pick_pad(r))


devgen.build = build
devgen.MODULES = devgen.MODULES + [("decoy", 2), ("deadend", 4)]
devgen.HARD_MODULES = devgen.HARD_MODULES + [("decoy", 2), ("deadend", 4)]

if __name__ == "__main__":
    devgen.main()
