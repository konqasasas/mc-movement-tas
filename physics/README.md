# physics: headless 1.12.2 player movement

`Physics.tick(world, state, input)` advances a survival player by one client tick and returns the new state. The result is bit-identical to Minecraft Java 1.12.2 for every field in `PlayerState`, as checked against the game's own code (see `oracle/`).

```java
BlockWorld world = new SparseBlockWorld().fill(-5, 63, -5, 5, 63, 5, BlockType.STONE);
PlayerState s = PlayerState.standing(0.5, 64, 0.5, 0.0F);
s = Physics.tick(world, s, PlayerInput.of(PlayerInput.W | PlayerInput.SPRINT | PlayerInput.JUMP, 0.0F));
```

## Model

- `PlayerInput`: the keys held this tick (W, A, S, D, jump, sprint, sneak) and the absolute yaw and pitch. The yaw is the raw, unwrapped `rotationYaw`.
- `PlayerState`: position, bounding box, velocity, ground and collision flags, sprint, jump cooldown, air acceleration, fall distance, size, the double-tap-W timer, last tick's movement input, and status effect amplifiers. `copy()` is a full independent copy and `firstDifference()` compares bitwise. The box is stored apart from the position because the game keeps them apart: a sneak height change rebuilds the box without moving the position.
- `BlockWorld`: block lookup. `SparseBlockWorld` is a mutable hash map world with `copy()`; `DenseBlockWorld` is an array over a fixed box and is faster for search.
- `PlayerState.standing(x, y, z, yaw)` is a player that has stood still for at least one tick, which is the start state for a course.

The tick follows the client's call chain: `EntityPlayerSP.onLivingUpdate` (keys, push out of blocks, sprint), `EntityLivingBase.onLivingUpdate` (velocity cutoff 0.003, jump, `travel`, `Entity.move` with sneak edge stop and step-up), `EntityPlayer.onLivingUpdate` (air acceleration, one tick behind the sprint flag), and `EntityPlayer.updateSize` (sneaking height 1.65 when there is room).

## Verified

- `VanillaGoldensTest`: 1,000 random runs of 200 ticks, all fields of all ticks, against hashes from the vanilla code. Runs with no Minecraft jar.
- `:oracle:test` and `:oracle:fuzz` run the vanilla code side by side and report the first differing field.
- Deliberately broken variants (sneak height, air acceleration timing, velocity cutoff, push-out direction) are caught by the fuzz.

## Not modelled yet

- Blocks with their own behaviour: slabs, stairs, fences and walls, ladders and vines, liquids, webs, soul sand, slime, beds, carpets, trapdoors and other partial shapes. Only full cubes (stone, ice, packed ice) exist so far.
- Status effects are in the state, but the oracle does not apply them yet, so speed, slowness and jump boost are not verified.
- Damage and death, flying, elytra, riding, using items, other entities, the world border.
- The client layer assumptions: auto-jump off, food above 6, no blindness. The `EntityPlayerSP` part and any server-to-client sprint flag updates need a recording from the real client.

## Speed

About 0.8 million ticks per second on one core with `DenseBlockWorld` (random courses, no tuning yet).
