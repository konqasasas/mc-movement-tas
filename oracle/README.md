# oracle: vanilla 1.12.2 differential tests

This module runs the game's own 1.12.2 player code headless and compares it with `physics`, field by field and bit by bit, every tick.

## How it works

- The official 1.12.2 server jar contains the same `Entity`, `EntityLivingBase`, `EntityPlayer`, `World` collision and `AxisAlignedBB` code as the client. `prepareMcpJar` remaps it to MCP names so it can be called directly.
- `OracleWorld` is a vanilla `World` whose blocks come from a `SparseBlockWorld`. `OraclePlayer` is a vanilla `EntityPlayer`.
- The client-only layer, `EntityPlayerSP.onLivingUpdate` (movement keys, push out of blocks, sprint and the double-tap window), is not in the server jar. It is ported by hand in `OraclePlayer`, the same way `Physics` ports it, so this module does not check it. That layer needs a recording from the real client.
- The only other deviation from vanilla is `updateFallState`: the server-only landing particles cast the world to `WorldServer`, so the oracle uses the client's path without them.

## Setup

```bash
./gradlew :oracle:prepareMcpJar
```

This downloads the server jar from Mojang (checked against SHA-1 `886945bfb2b978778c3a0288fd7fab09d315b25f`), the MCP 1.12.2 SRG and stable_39 name tables from the Forge maven, and SpecialSource from Maven Central, then writes `oracle/lib/minecraft_server-1.12.2-mcp.jar`. The jar is ignored by git and never committed. Offline, pass local copies: `-PmcServerJar=<server.jar> -PmcpConfDir=<dir with joined.srg, fields.csv, methods.csv>`.

Without the jar, every task in this module is skipped and the rest of the build is unaffected.

## Tasks

| Command | What it does |
| --- | --- |
| `./gradlew :oracle:test` | M1 gate: 1,000 random runs of 200 ticks must match vanilla on every field of every tick |
| `./gradlew :oracle:fuzz -Pruns=10000 -Pticks=400 -PfirstSeed=1` | Same comparison at any size; prints the first differing tick and field. `-Pcourses=shapes` uses courses of every modelled block |
| `./gradlew :oracle:writeGoldens` | Regenerates `physics/src/test/resources/vanilla-goldens.txt` and `vanilla-goldens-shapes.txt`, the vanilla trajectory hashes that `physics` checks itself against without the jar |

Random scenarios come from `physics`' `RandomScenario`: courses of stone, ice and packed ice with gaps, steps, pillars and ceilings; standing, mid-air and embedded starts; inputs that hold keys for a few ticks, turn smoothly or jump to arbitrary angles, including yaws in the millions.
