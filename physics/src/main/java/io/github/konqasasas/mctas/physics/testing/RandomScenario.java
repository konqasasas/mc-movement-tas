package io.github.konqasasas.mctas.physics.testing;

import io.github.konqasasas.mctas.physics.BlockType;
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
 * with pools of water or lava ({@link #generateFluids}); the inputs hold
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

    /** The course kinds, by the names the fuzz tools take. */
    public static final String[] COURSES = {"full", "shapes", "fluids"};

    /** {@link #generate}, {@link #generateShapes} or {@link #generateFluids} by name. */
    public static RandomScenario generate(String course, long seed, int ticks) {
        if (course.equals("full")) {
            return generate(seed, ticks);
        } else if (course.equals("shapes")) {
            return generateShapes(seed, ticks);
        } else if (course.equals("fluids")) {
            return generateFluids(seed, ticks);
        }
        throw new IllegalArgumentException("unknown course kind " + course + ", expected full, shapes or fluids");
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

    private static RandomScenario generate(long seed, int ticks, int kind) {
        Random r = new Random(seed);
        SparseBlockWorld w = new SparseBlockWorld();
        int base = 64;
        boolean shapes = kind != 0;
        BlockType[] palette = shapes ? shapeCourse(r, w, base) : fullBlockCourse(r, w, base);
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

        List<PlayerInput> inputs = new ArrayList<PlayerInput>(ticks);
        int keys = 0;
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
            inputs.add(new PlayerInput(k, yaw, pitch));
        }
        return new RandomScenario(seed, w, start, inputs, standing);
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
    private static BlockType[] shapeCourse(Random r, SparseBlockWorld w, int base) {
        List<BlockType> all = new ArrayList<BlockType>(VanillaBlocks.all());
        all.remove(BlockType.AIR);
        for (java.util.Iterator<BlockType> it = all.iterator(); it.hasNext(); ) {
            if (it.next() instanceof VanillaBlocks.Liquid) {
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
