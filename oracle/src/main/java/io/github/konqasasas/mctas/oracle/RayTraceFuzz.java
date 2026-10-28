package io.github.konqasasas.mctas.oracle;

import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.Placement;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import io.github.konqasasas.mctas.physics.VanillaBlocks;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Compares {@link Placement#rayTraceBlocks} with {@code World.rayTraceBlocks(start, end, false, false, true)}
 * on random clusters of every block in the catalog, so like and unlike neighbours meet and change the
 * selection boxes of fences, walls, panes, chests, doors and chorus plants. Rays start anywhere,
 * including inside blocks and on block edges, and some run along an axis.
 *
 * <p>Usage: {@code RayTraceFuzz [worlds] [rays per world] [firstSeed]}.
 */
public final class RayTraceFuzz {

    /** Blocks whose selection box the engine does not compute. */
    private static final List<String> SKIPPED = java.util.Arrays.asList("minecraft:vine", "minecraft:redstone_wire");

    private RayTraceFuzz() {
    }

    /** Null when every ray agrees, otherwise a description of the first that does not. */
    public static String check(long seed, int rays) {
        new VanillaOracle(new SparseBlockWorld());
        Random r = new Random(seed);
        List<BlockType> all = new ArrayList<BlockType>();
        for (BlockType t : VanillaBlocks.all()) {
            if (!SKIPPED.contains(VanillaBlocks.blockId(t.name()))) {
                all.add(t);
            }
        }
        // A few kinds at a time, so like blocks connect.
        BlockType[] palette = new BlockType[1 + r.nextInt(4)];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = all.get(r.nextInt(all.size()));
        }
        double fill = 0.2 + r.nextDouble() * 0.6;
        SparseBlockWorld engine = new SparseBlockWorld();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                for (int z = 0; z < 5; z++) {
                    if (r.nextDouble() < fill) {
                        engine.setBlock(x, y, z, palette[r.nextInt(palette.length)]);
                    }
                }
            }
        }
        OracleWorld vanilla = new OracleWorld(engine);
        for (int i = 0; i < rays; i++) {
            double[] start = {point(r), point(r), point(r)};
            double[] end;
            if (r.nextDouble() < 0.2) {
                end = start.clone();
                end[r.nextInt(3)] += (r.nextBoolean() ? 1 : -1) * r.nextDouble() * 6.0;
            } else {
                end = new double[] {point(r), point(r), point(r)};
            }
            Placement.Hit h = Placement.rayTraceBlocks(engine, start, end);
            RayTraceResult v = vanilla.rayTraceBlocks(new Vec3d(start[0], start[1], start[2]),
                    new Vec3d(end[0], end[1], end[2]), false, false, true);
            String got = h == null ? "miss" : h.x + " " + h.y + " " + h.z + " " + h.side + " " + h.hitX + " " + h.hitY + " " + h.hitZ;
            String want = v == null || v.typeOfHit != RayTraceResult.Type.BLOCK ? "miss"
                    : v.getBlockPos().getX() + " " + v.getBlockPos().getY() + " " + v.getBlockPos().getZ() + " "
                    + v.sideHit.name() + " " + v.hitVec.x + " " + v.hitVec.y + " " + v.hitVec.z;
            if (!got.equals(want)) {
                return "seed " + seed + " ray " + i + " from " + java.util.Arrays.toString(start) + " to "
                        + java.util.Arrays.toString(end) + "\n  physics: " + got + "\n  vanilla: " + want
                        + (h == null ? "" : "\n  block:   " + engine.getBlock(h.x, h.y, h.z).name())
                        + (v == null || v.typeOfHit != RayTraceResult.Type.BLOCK ? "" : "\n  vanilla block: " + vanilla.getBlockState(v.getBlockPos()));
            }
        }
        return null;
    }

    /** A coordinate around the cluster: often on a block edge or a sixteenth, sometimes anywhere. */
    private static double point(Random r) {
        double roll = r.nextDouble();
        if (roll < 0.15) {
            return r.nextInt(7) - 1;
        } else if (roll < 0.3) {
            return (r.nextInt(7 * 16) - 16) / 16.0;
        }
        return -1.0 + r.nextDouble() * 7.0;
    }

    public static void main(String[] args) {
        int worlds = args.length > 0 ? Integer.parseInt(args[0]) : 1000;
        int rays = args.length > 1 ? Integer.parseInt(args[1]) : 200;
        long first = args.length > 2 ? Long.parseLong(args[2]) : 1L;
        int failures = 0;
        for (long seed = first; seed < first + worlds; seed++) {
            String msg = check(seed, rays);
            if (msg != null) {
                failures++;
                if (failures <= 5) {
                    System.out.println(msg);
                }
            }
        }
        System.out.printf("%d worlds x %d rays: %d mismatching worlds%n", worlds, rays, failures);
        if (failures > 0) {
            System.exit(1);
        }
    }
}
