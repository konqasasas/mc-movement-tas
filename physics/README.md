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
- `VanillaBlocks`: the block states the engine models, by vanilla state name such as `minecraft:oak_stairs[facing=north,half=bottom]`. Only properties that change collision are part of the name. Shapes that depend on the neighbours (stair shape, fence, wall and pane connections, double chests) are computed from the world on each lookup, as the game does, so placing a block next to a fence changes the fence.
- `PlayerState.standing(x, y, z, yaw)` is a player that has stood still for at least one tick, which is the start state for a course.

The tick follows the client's call chain: `Entity.onEntityUpdate` (water current and the `inWater` flag, lava halving the fall distance), `EntityPlayerSP.onLivingUpdate` (keys, push out of blocks, sprint), `EntityLivingBase.onLivingUpdate` (velocity cutoff 0.003, jump or swim-up, `travel` with its walking, water and lava branches, `Entity.move` with sneak edge stop and step-up), `EntityPlayer.onLivingUpdate` (air acceleration, one tick behind the sprint flag), and `EntityPlayer.updateSize` (sneaking height 1.65 when there is room).

## Verified

- `VanillaGoldensTest`: 1,000 random runs of 200 ticks on full-block courses, 1,000 on courses of every stable solid block and 1,000 on those with pools of water or lava, all fields of all ticks, against hashes from the vanilla code. Runs with no Minecraft jar.
- `:oracle:test` also checks every `VanillaBlocks` entry against the vanilla block state of the same name: collision boxes and face shapes among 20,000 random three-layer neighbour layouts (and 20,000 more of only doors and chorus plants), normal cube, slipperiness, material flags, that a state sent to the client keeps its name, and the current of every liquid among 20,000 random layouts of liquids and blocks.
- `:oracle:test` and `:oracle:fuzz` run the vanilla code side by side and report the first differing field.
- Deliberately broken variants (sneak height, air acceleration timing, velocity cutoff, push-out direction) are caught by the fuzz.

## Not modelled yet

- Every 1.12.2 block is in `VanillaBlocks` except `portal`, `end_portal`, `end_gateway` and `piston_extension`. Blocks whose shape depends on the neighbours (stairs, fences, walls, panes, chests, doors, chorus plants) and blocks that act on the player (slime, beds, soul sand, webs, ladders, vines, trapdoors over ladders, water and lava) are written by hand; every other block comes from `vanilla-blocks.tsv`, generated from the game by `./gradlew :oracle:writeBlockTable` (boxes, faces, slipperiness, normal cube, material flags). The table only keeps the states a world can hold (a moving piston head's `short` is dropped).
- Damage of any kind (cactus, magma, fire, lava, falls): in the real game damage lets the server overwrite the client's velocity. The recorder's fuzz makes the player invulnerable.
- Blocks that change by themselves or by the player (falling sand and gravel, growing crops, decaying leaves, farmland trampled by a fall, pressure plates, tripwires and detector rails that the player powers, and the doors and pistons they move). The engine's world never changes; these blocks carry `BlockType.UNSTABLE`, and the fuzz courses leave them out.
- Status effects are in the state, but the oracle does not apply them yet, so speed, slowness and jump boost are not verified.
- Damage and death, flying, elytra, riding, using items, other entities, the world border.
- The client layer assumptions: auto-jump off, food above 6, no blindness. The `EntityPlayerSP` part and any server-to-client sprint flag updates need a recording from the real client.

## Speed

About 0.8 million ticks per second on one core with `DenseBlockWorld` (random courses, no tuning yet).
