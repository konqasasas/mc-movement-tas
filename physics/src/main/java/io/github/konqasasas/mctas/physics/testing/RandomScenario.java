package io.github.konqasasas.mctas.physics.testing;

import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A reproducible random course and input sequence for differential testing. The world is a small
 * parkour-like field of full blocks with gaps, steps, pillars, ceilings and ice; the inputs hold
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

    public static RandomScenario generate(long seed, int ticks) {
        Random r = new Random(seed);
        SparseBlockWorld w = new SparseBlockWorld();
        int size = 6 + r.nextInt(10);
        int base = 64;
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
                w.fill(px, base, pz, px, base + 1, pz, r.nextBoolean() ? BlockType.STONE : BlockType.PACKED_ICE);
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
