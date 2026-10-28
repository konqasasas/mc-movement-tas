package io.github.konqasasas.mctas.physics.testing;

import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.Facing;
import io.github.konqasasas.mctas.physics.Hotbar;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import io.github.konqasasas.mctas.physics.VanillaBlocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A reproducible random course and input sequence for differential testing. The world is a small
 * parkour-like field with gaps, steps, pillars and ceilings, either of full blocks and ice
 * ({@link #generate}), of every solid block the engine models ({@link #generateShapes}), or of those
 * with pools of water or lava ({@link #generateFluids}), or of those with a hotbar of full blocks to
 * place ({@link #generatePlacing}); the inputs hold
 * keys for a few ticks at a time and mix smooth turning with sudden angle jumps.
 */
public final class RandomScenario {

    public final long seed;
    public final SparseBlockWorld world;
    public final PlayerState start;
    public final List<PlayerInput> inputs;
    /** True when {@link #start} is a player standing on the ground, which the real game can set up by teleport. */
    public final boolean standingStart;

    private RandomScenario(long seed, SparseBlockWorld world, PlayerState start, List<PlayerInput> inputs,
                           boolean standingStart) {
        this.seed = seed;
        this.world = world;
        this.start = start;
        this.inputs = inputs;
        this.standingStart = standingStart;
    }

    /** Blocks whose selection box or replaceability the engine cannot compute from the catalog name. */
    private static final List<String> UNMODELLED_CLICKS = java.util.Arrays.asList(
            "minecraft:vine", "minecraft:redstone_wire", "minecraft:double_plant");

    /** The course kinds, by the names the fuzz tools take. */
    public static final String[] COURSES = {"full", "shapes", "fluids", "place", "press"};

    /** {@link #generate}, {@link #generateShapes} or {@link #generateFluids} by name. */
    public static RandomScenario generate(String course, long seed, int ticks) {
        if (course.equals("full")) {
            return generate(seed, ticks);
        } else if (course.equals("shapes")) {
            return generateShapes(seed, ticks);
        } else if (course.equals("fluids")) {
            return generateFluids(seed, ticks);
        } else if (course.equals("place")) {
            return generatePlacing(seed, ticks);
        } else if (course.equals("press")) {
            return generatePressing(seed, ticks);
        }
        throw new IllegalArgumentException("unknown course kind " + course + ", expected full, shapes, fluids, place or press");
    }

    /** Full blocks and ice only. */
    public static RandomScenario generate(long seed, int ticks) {
        return generate(seed, ticks, 0);
    }

    /** Courses built from every solid block in {@link VanillaBlocks}: slabs, stairs, fences, walls, panes, ... */
    public static RandomScenario generateShapes(long seed, int ticks) {
        return generate(seed, ticks, 1);
    }

    /** {@link #generateShapes} courses with pools and puddles of still water or lava (one of the two per course). */
    public static RandomScenario generateFluids(long seed, int ticks) {
        return generate(seed, ticks, 2);
    }

    /**
     * {@link #generateShapes} courses, without the blocks whose right-click the engine does not model,
     * and a hotbar of full blocks; the inputs right-click, switch slots and often look down.
     */
    public static RandomScenario generatePlacing(long seed, int ticks) {
        return generate(seed, ticks, 3);
    }

    /**
     * {@link #generatePlacing} courses with stone posts that carry buttons and levers on their sides and
     * tops, some next to blocks that react to power; the hotbar is empty half the time, and the inputs
     * often look level, at the posts.
     */
    public static RandomScenario generatePressing(long seed, int ticks) {
        return generate(seed, ticks, 4);
    }

    private static RandomScenario generate(long seed, int ticks, int kind) {
        Random r = new Random(seed);
        SparseBlockWorld w = new SparseBlockWorld();
        int base = 64;
        boolean shapes = kind != 0;
        boolean pressing = kind == 4;
        boolean placing = kind == 3 || pressing;
        BlockType[] palette = shapes ? shapeCourse(r, w, base, placing) : fullBlockCourse(r, w, base);
        if (pressing) {
            addPosts(r, w, base);
        }
        if (kind == 2) {
            addFluids(r, w, base, palette);
        }
        double sx = 0.3 + r.nextDouble() * 1.4 - 0.5;
        double sz = 0.3 + r.nextDouble() * 1.4 - 0.5;
        float yaw0 = (r.nextFloat() - 0.5F) * 720.0F;
        PlayerState start = PlayerState.standing(sx, base, sz, yaw0);
        double variant = r.nextDouble();
        boolean standing = true;
        if (variant < 0.15) {
            // Pillars that may overlap the player, so the client's push-out-of-blocks fires.
            for (int i = 1 + r.nextInt(3); i > 0; i--) {
                int px = r.nextInt(3) - 1;
                int pz = r.nextInt(3) - 1;
                if (shapes) {
                    w.setBlock(px, base, pz, palette[r.nextInt(palette.length)]);
                    w.setBlock(px, base + 1, pz, palette[r.nextInt(palette.length)]);
                } else {
                    w.fill(px, base, pz, px, base + 1, pz, r.nextBoolean() ? BlockType.STONE : BlockType.PACKED_ICE);
                }
            }
        } else if (variant < 0.45) {
            // Mid-air entry with arbitrary velocity and carried-over flags.
            standing = false;
            start.setPosition(sx, base + r.nextDouble() * 2.5, sz);
            start.motionX = (r.nextDouble() - 0.5) * 1.2;
            start.motionY = (r.nextDouble() - 0.5) * 1.0;
            start.motionZ = (r.nextDouble() - 0.5) * 1.2;
            start.onGround = false;
            start.collidedVertically = false;
            start.sprinting = r.nextBoolean();
            start.jumpMovementFactor = r.nextBoolean() ? 0.026F : 0.02F;
            start.jumpTicks = r.nextInt(11);
            start.fallDistance = r.nextFloat() * 5.0F;
            start.sprintToggleTimer = r.nextInt(8);
            start.prevMoveForward = r.nextBoolean() ? 1.0F : 0.0F;
        }

        if (placing) {
            List<BlockType> blocks = new ArrayList<BlockType>();
            for (BlockType t : VanillaBlocks.all()) {
                if (t.isPlaceable()) {
                    blocks.add(t);
                }
            }
            boolean emptyHand = pressing && r.nextBoolean();
            for (int i = 0; i < Hotbar.SLOTS; i++) {
                if (!emptyHand && r.nextDouble() < 0.6) {
                    start.hotbar = start.hotbar.with(i, blocks.get(r.nextInt(blocks.size())), 1 + r.nextInt(r.nextBoolean() ? 4 : 64));
                }
            }
            start.selectedSlot = r.nextInt(Hotbar.SLOTS);
            start.rightClickDelay = r.nextInt(5);
            start.prevUse = r.nextBoolean();
        }

        List<PlayerInput> inputs = new ArrayList<PlayerInput>(ticks);
        int keys = 0;
        boolean use = false;
        float yaw = yaw0;
        float turnRate = 0.0F;
        int hold = 0;
        for (int t = 0; t < ticks; t++) {
            if (hold-- <= 0) {
                keys = randomKeys(r);
                hold = r.nextInt(r.nextBoolean() ? 3 : 15);
            }
            int k = keys;
            if (r.nextDouble() < 0.05) {
                k ^= 1 << r.nextInt(7);
            }
            double roll = r.nextDouble();
            if (roll < 0.05) {
                yaw = (r.nextFloat() - 0.5F) * 720.0F;
            } else if (roll < 0.07) {
                // Large unwrapped yaws exercise float precision in the sine lookup.
                yaw = (float) ((r.nextDouble() - 0.5) * 2.0e6);
            } else if (roll < 0.3) {
                turnRate = (r.nextFloat() - 0.5F) * 30.0F;
            }
            yaw += turnRate;
            float pitch = (r.nextFloat() - 0.5F) * 180.0F;
            if (!placing) {
                inputs.add(new PlayerInput(k, yaw, pitch));
                continue;
            }
            if (r.nextDouble() < 0.2) {
                use = !use;
            }
            if (use) {
                k |= PlayerInput.USE;
            }
            if (pressing && r.nextDouble() < 0.7) {
                // Level, at the posts around.
                pitch = -20.0F + r.nextFloat() * 70.0F;
            } else if (r.nextDouble() < 0.7) {
                // Down at the floor or the block just below, as when bridging or pillaring.
                pitch = 30.0F + r.nextFloat() * 60.0F;
            }
            int slot = r.nextDouble() < 0.08 ? r.nextInt(Hotbar.SLOTS) : -1;
            inputs.add(new PlayerInput(k, yaw, pitch, slot));
        }
        return new RandomScenario(seed, w, start, inputs, standing);
    }

    /**
     * Stone posts two blocks high with buttons and levers on the free sides and the top, each facing
     * away from the stone it hangs on; next to some, a block that reacts to power.
     */
    private static void addPosts(Random r, SparseBlockWorld w, int base) {
        String[] reacting = {"minecraft:redstone_lamp", "minecraft:trapdoor[facing=north,half=bottom,open=false]",
                "minecraft:piston[extended=false,facing=up]", "minecraft:water[level=0]", "minecraft:noteblock",
                "minecraft:sand"};
        for (int posts = 2 + r.nextInt(5); posts > 0; posts--) {
            int px = r.nextInt(9) - 4;
            int pz = r.nextInt(9) - 4;
            if (Math.abs(px) <= 2 && Math.abs(pz) <= 2 || !clear(w, px, base, pz)) {
                // Keep the start free, and every button and lever on stone.
                continue;
            }
            for (int x = px - 1; x <= px + 1; x++) {
                for (int z = pz - 1; z <= pz + 1; z++) {
                    w.fill(x, base, z, x, base + 3, z, BlockType.AIR);
                }
            }
            int h = 1 + r.nextInt(2);
            w.fill(px, base, pz, px, base + h - 1, pz, BlockType.STONE);
            for (int y = base; y < base + h; y++) {
                for (Facing f : Facing.HORIZONTALS) {
                    int bx = px + f.dx;
                    int bz = pz + f.dz;
                    if (Math.abs(bx) <= 1 && Math.abs(bz) <= 1 || !w.getBlock(bx, y, bz).isAir() || r.nextDouble() < 0.4) {
                        continue;
                    }
                    w.setBlock(bx, y, bz, attached(r, f.name().toLowerCase(java.util.Locale.ROOT), false));
                }
            }
            if (r.nextBoolean()) {
                w.setBlock(px, base + h, pz, attached(r, "up", true));
            }
            if (r.nextDouble() < 0.4) {
                Facing f = Facing.HORIZONTALS[r.nextInt(4)];
                int y = base + r.nextInt(h + 1);
                if (w.getBlock(px - f.dx, y, pz - f.dz).isAir()) {
                    w.setBlock(px - f.dx, y, pz - f.dz, VanillaBlocks.require(reacting[r.nextInt(reacting.length)]));
                }
            }
        }
    }

    /** True if no other post stands within two blocks of (px, pz). */
    private static boolean clear(SparseBlockWorld w, int px, int base, int pz) {
        for (int x = px - 2; x <= px + 2; x++) {
            for (int z = pz - 2; z <= pz + 2; z++) {
                for (int y = base; y <= base + 3; y++) {
                    String id = VanillaBlocks.blockId(w.getBlock(x, y, z).name());
                    if (id.endsWith("_button") || id.equals("minecraft:lever")) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** A button or lever facing {@code facing}, on and off at random. */
    private static BlockType attached(Random r, String facing, boolean top) {
        double roll = r.nextDouble();
        if (roll < 0.25) {
            String f = top ? (r.nextBoolean() ? "up_x" : "up_z") : facing;
            return VanillaBlocks.require("minecraft:lever[facing=" + f + "]");
        }
        String id = roll < 0.6 ? "minecraft:stone_button" : "minecraft:wooden_button";
        return VanillaBlocks.require(id + "[facing=" + facing + ",powered=" + (r.nextDouble() < 0.1) + "]");
    }

    private static BlockType[] fullBlockCourse(Random r, SparseBlockWorld w, int base) {
        int size = 6 + r.nextInt(10);
        BlockType[] solids = {BlockType.STONE, BlockType.STONE, BlockType.STONE, BlockType.ICE, BlockType.PACKED_ICE};
        double holeChance = r.nextDouble() * 0.4;
        for (int x = -size; x <= size; x++) {
            for (int z = -size; z <= size; z++) {
                boolean centre = Math.abs(x) <= 1 && Math.abs(z) <= 1;
                if (!centre && r.nextDouble() < holeChance) {
                    continue;
                }
                BlockType t = solids[r.nextInt(solids.length)];
                int h = centre ? 0 : r.nextInt(10) == 0 ? 1 : 0;
                w.fill(x, base - 1, z, x, base - 1 + h, z, t);
                double roll = r.nextDouble();
                if (!centre && roll < 0.04) {
                    w.fill(x, base, z, x, base + 2, z, BlockType.STONE);
                } else if (roll < 0.10) {
                    // Ceiling for head hitters, two or three blocks above the floor.
                    w.setBlock(x, base + 2 + r.nextInt(2) + h, z, solids[r.nextInt(solids.length)]);
                }
            }
        }
        // A lower floor so falls end somewhere.
        if (r.nextBoolean()) {
            w.fill(-size - 4, base - 6, -size - 4, size + 4, base - 6, size + 4, BlockType.STONE);
        }

        return solids;
    }

    /**
     * A field of a few block kinds at a time, so like blocks meet and connect: floors, raised blocks
     * to step or jump onto, walls, and ceilings. The 3x3 start area is stone with two blocks of air.
     */
    private static BlockType[] shapeCourse(Random r, SparseBlockWorld w, int base, boolean placing) {
        List<BlockType> all = new ArrayList<BlockType>(VanillaBlocks.all());
        all.remove(BlockType.AIR);
        for (java.util.Iterator<BlockType> it = all.iterator(); it.hasNext(); ) {
            BlockType t = it.next();
            // Unstable blocks fall, grow or get powered by the player in the real game, so a course of them
            // would not stay as built.
            if (t instanceof VanillaBlocks.Liquid || t.isExcept(BlockType.UNSTABLE)) {
                it.remove();
            } else if (placing && (UNMODELLED_CLICKS.contains(VanillaBlocks.blockId(t.name())) || t.activates()
                    || t.isFragile())) {
                // Placing next to these changes them in the real game, and clicking them opens or toggles them.
                it.remove();
            }
        }
        int kinds = 2 + r.nextInt(5);
        BlockType[] palette = new BlockType[kinds + 1];
        palette[0] = BlockType.STONE;
        List<BlockType> special = new ArrayList<BlockType>();
        for (BlockType t : all) {
            if (VanillaBlocks.actsOnPlayer(t)) {
                special.add(t);
            }
        }
        for (int i = 1; i <= kinds; i++) {
            // Blocks with their own effects are few in the catalog, so they get a share of their own.
            List<BlockType> from = r.nextDouble() < 0.35 ? special : all;
            palette[i] = from.get(r.nextInt(from.size()));
        }
        int size = 4 + r.nextInt(8);
        double holeChance = r.nextDouble() * 0.3;
        double raisedChance = r.nextDouble() * 0.5;
        for (int x = -size; x <= size; x++) {
            for (int z = -size; z <= size; z++) {
                if (Math.abs(x) <= 1 && Math.abs(z) <= 1) {
                    w.setBlock(x, base - 1, z, BlockType.STONE);
                    continue;
                }
                if (r.nextDouble() >= holeChance) {
                    w.setBlock(x, base - 1, z, palette[r.nextInt(palette.length)]);
                }
                if (r.nextDouble() < raisedChance) {
                    w.setBlock(x, base, z, palette[r.nextInt(palette.length)]);
                    if (r.nextDouble() < 0.15) {
                        w.setBlock(x, base + 1, z, palette[r.nextInt(palette.length)]);
                    }
                }
                if (r.nextDouble() < 0.1) {
                    w.setBlock(x, base + 2 + r.nextInt(2), z, palette[r.nextInt(palette.length)]);
                }
            }
        }
        if (r.nextBoolean()) {
            w.fill(-size - 4, base - 6, -size - 4, size + 4, base - 6, size + 4, BlockType.STONE);
        }
        return palette;
    }

    /**
     * Pools one to three blocks deep, some filled up to the feet, and single puddles on the floor,
     * including the start area. Only the still blocks ({@code minecraft:water}, {@code minecraft:lava}):
     * the recorder places them without block updates, so they stay as built.
     */
    private static void addFluids(Random r, SparseBlockWorld w, int base, BlockType[] palette) {
        String id = r.nextDouble() < 0.3 ? "minecraft:lava" : "minecraft:water";
        BlockType[] liquids = new BlockType[4];
        for (int i = 0; i < liquids.length; i++) {
            int level = r.nextDouble() < 0.6 ? 0 : 1 + r.nextInt(15);
            liquids[i] = VanillaBlocks.require(id + "[level=" + level + "]");
        }
        for (int pools = 1 + r.nextInt(4); pools > 0; pools--) {
            int cx = r.nextInt(13) - 6;
            int cz = r.nextInt(13) - 6;
            int hx = r.nextInt(3);
            int hz = r.nextInt(3);
            int depth = 1 + r.nextInt(3);
            boolean full = r.nextDouble() < 0.4;
            for (int x = cx - hx; x <= cx + hx; x++) {
                for (int z = cz - hz; z <= cz + hz; z++) {
                    if (Math.abs(x) <= 1 && Math.abs(z) <= 1) {
                        // Keep the stone the player starts on.
                        continue;
                    }
                    w.setBlock(x, base - 1 - depth, z, palette[r.nextInt(palette.length)]);
                    for (int y = base - depth; y <= base - 1; y++) {
                        w.setBlock(x, y, z, liquids[r.nextInt(liquids.length)]);
                    }
                    w.setBlock(x, base, z, full ? liquids[r.nextInt(liquids.length)] : BlockType.AIR);
                }
            }
        }
        double puddles = r.nextDouble() * 0.15;
        for (int x = -7; x <= 7; x++) {
            for (int z = -7; z <= 7; z++) {
                boolean centre = Math.abs(x) <= 1 && Math.abs(z) <= 1;
                if (r.nextDouble() < (centre ? puddles * 2.0 : puddles) && w.getBlock(x, base, z).isAir()) {
                    w.setBlock(x, base, z, liquids[r.nextInt(liquids.length)]);
                }
            }
        }
    }

    private static int randomKeys(Random r) {
        int k = 0;
        if (r.nextDouble() < 0.75) k |= PlayerInput.W;
        if (r.nextDouble() < 0.15) k |= PlayerInput.S;
        if (r.nextDouble() < 0.3) k |= PlayerInput.A;
        if (r.nextDouble() < 0.3) k |= PlayerInput.D;
        if (r.nextDouble() < 0.35) k |= PlayerInput.JUMP;
        if (r.nextDouble() < 0.6) k |= PlayerInput.SPRINT;
        if (r.nextDouble() < 0.15) k |= PlayerInput.SNEAK;
        return k;
    }
}
