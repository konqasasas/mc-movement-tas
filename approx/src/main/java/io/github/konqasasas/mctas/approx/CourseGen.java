package io.github.konqasasas.mctas.approx;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import io.github.konqasasas.mctas.problem.world.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Random jump courses for the larger problem sets (problems/v1-*).
 *
 * <p>Every course starts like v0: the stone runway {@code x = 0, y = 64, z = -30..0}, spawn at its far
 * end and the start box on its last block. Then come {@code jumps} platforms, each one or two blocks of
 * a block type drawn from {@link #BLOCKS}, placed further along +z with a sideways offset and a height
 * change. A placement is kept only when a sprint jump from the previous platform could plausibly reach
 * it ({@link Flight}, with a margin); the solver decides whether it really can. The goal is landing on
 * the last platform. Blocks that hurt, trample, bounce or change by themselves are left out, as the
 * README asks.
 */
public final class CourseGen {
    /** Block states and their weights. */
    static final Object[][] BLOCKS = {
        {"minecraft:stone", 6},
        {"minecraft:stone_slab[half=bottom]", 2},
        {"minecraft:stone_slab[half=top]", 1},
        {"minecraft:fence", 1},
        {"minecraft:cobblestone_wall", 1},
        {"minecraft:glass_pane", 1},
        {"minecraft:iron_bars", 1},
        {"minecraft:stone_stairs[facing=south,half=bottom]", 1},
        {"minecraft:stone_stairs[facing=north,half=bottom]", 1},
        {"minecraft:ice", 1},
        {"minecraft:packed_ice", 1},
        {"minecraft:soul_sand", 1},
        {"minecraft:chest", 1},
        {"minecraft:enchanting_table", 1},
        {"minecraft:end_rod[facing=up]", 1},
        {"minecraft:skull[facing=up]", 1},
        {"minecraft:anvil[facing=north]", 1},
        {"minecraft:trapdoor[facing=north,half=bottom,open=false]", 1},
        {"minecraft:daylight_detector", 1},
        {"minecraft:sea_lantern", 1},
        {"minecraft:carpet", 1},
    };

    private static final class Fill {
        final int x1, y1, z1, x2, y2, z2;
        final String block;

        Fill(int x1, int y1, int z1, int x2, int y2, int z2, String block) {
            this.x1 = x1;
            this.y1 = y1;
            this.z1 = z1;
            this.x2 = x2;
            this.y2 = y2;
            this.z2 = z2;
            this.block = block;
        }
    }

    private CourseGen() {
    }

    /** The problem file text for a course. */
    public static String generate(String id, long seed, int jumps) {
        Random r = new Random(seed);
        List<Fill> fills = new ArrayList<Fill>();
        fills.add(new Fill(0, 64, -30, 0, 64, 0, "minecraft:stone"));
        Route.Surface cur = surface(new int[] {0, 64, 0}, new int[] {0, 64, 0}, "minecraft:stone");
        int cx = 0, cy = 64, cz = 0;
        int placed = 0;
        Route.Surface last = cur;
        List<String> kinds = new ArrayList<String>();
        for (int attempt = 0; placed < jumps && attempt < 10000; attempt++) {
            String block = pick(r);
            int dy = new int[] {-2, -1, -1, 0, 0, 0, 0, 1, 1}[r.nextInt(9)];
            int dz = 1 + r.nextInt(5);
            int dx = r.nextInt(5) - 2;
            int len = r.nextDouble() < 0.2 ? 2 : 1;
            int nx = cx + dx, ny = cy + dy, nz = cz + dz;
            if (ny < 50 || ny > 90) continue;
            int[] from = {nx, ny, nz};
            int[] to = {nx, ny, nz + len - 1};
            Route.Surface s = surface(from, to, block);
            if (s == null) continue;
            double dTop = s.top - cur.top;
            if (dTop > 1.0) continue;
            double gap = Route.rectGap(cur, s);
            if (gap < 0.5 && dTop < 0.6) continue;
            int landing = Flight.landingTick(dTop);
            if (landing < 0) continue;
            double reach = Flight.jumpDistance(0.2, landing - 1) - 0.35;
            if (gap > reach) continue;
            fills.add(new Fill(from[0], from[1], from[2], to[0], to[1], to[2], block));
            kinds.add(block.replace("minecraft:", ""));
            cur = s;
            last = s;
            cx = nx;
            cy = ny;
            cz = nz + len - 1;
            placed++;
        }
        JsonObject root = new JsonObject();
        root.addProperty("format", "mctas-problem");
        root.addProperty("version", 1);
        root.addProperty("id", id);
        root.addProperty("title", jumps + " jumps, generated (seed " + seed + "): " + String.join(", ", kinds));
        root.addProperty("mcVersion", "1.12.2");
        JsonObject world = new JsonObject();
        world.addProperty("source", "inline");
        JsonArray fa = new JsonArray();
        for (Fill f : fills) {
            JsonObject o = new JsonObject();
            o.add("from", ints(f.x1, f.y1, f.z1));
            o.add("to", ints(f.x2, f.y2, f.z2));
            o.addProperty("block", f.block);
            fa.add(o);
        }
        world.add("fills", fa);
        root.add("world", world);
        JsonObject spawn = new JsonObject();
        spawn.add("pos", nums(0.5, 65.0, -29.5));
        spawn.addProperty("yaw", 0.0);
        spawn.addProperty("pitch", 0.0);
        root.add("spawn", spawn);
        root.add("start", box(-1.0, 60.0, 0.0, 2.0, 70.0, 1.0));
        root.add("goal", box(last.minX, last.top, last.minZ, last.maxX, last.top, last.maxZ));
        JsonObject limits = new JsonObject();
        limits.addProperty("maxTicks", 400 + 40 * jumps);
        limits.addProperty("voidY", 0.0);
        root.add("limits", limits);
        return new GsonBuilder().setPrettyPrinting().create().toJson(root);
    }

    private static String pick(Random r) {
        int total = 0;
        for (Object[] b : BLOCKS) total += (Integer) b[1];
        int k = r.nextInt(total);
        for (Object[] b : BLOCKS) {
            k -= (Integer) b[1];
            if (k < 0) return (String) b[0];
        }
        throw new AssertionError();
    }

    /** The standable top of a platform filled from {@code from} to {@code to}, or null if it has none. */
    private static Route.Surface surface(int[] from, int[] to, String block) {
        SparseBlockWorld w = new SparseBlockWorld();
        w.fill(from[0], from[1], from[2], to[0], to[1], to[2], Blocks.byId(block));
        List<Route.Surface> all = Route.surfaces(w);
        if (all.size() != (to[2] - from[2] + 1)) return null;
        Route.Surface first = all.get(0);
        double minX = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (Route.Surface s : all) {
            if (Math.abs(s.top - first.top) > 1e-9) return null;
            minX = Math.min(minX, s.minX + 0.3);
            minZ = Math.min(minZ, s.minZ + 0.3);
            maxX = Math.max(maxX, s.maxX - 0.3);
            maxZ = Math.max(maxZ, s.maxZ - 0.3);
        }
        return new Route.Surface(from[0], from[1], from[2], first.top, minX, minZ, maxX, maxZ);
    }

    private static JsonArray ints(int a, int b, int c) {
        JsonArray x = new JsonArray();
        x.add(new JsonPrimitive(a));
        x.add(new JsonPrimitive(b));
        x.add(new JsonPrimitive(c));
        return x;
    }

    private static JsonArray nums(double a, double b, double c) {
        JsonArray x = new JsonArray();
        x.add(new JsonPrimitive(a));
        x.add(new JsonPrimitive(b));
        x.add(new JsonPrimitive(c));
        return x;
    }

    private static JsonObject box(double x1, double y1, double z1, double x2, double y2, double z2) {
        JsonObject o = new JsonObject();
        o.add("min", nums(x1, y1, z1));
        o.add("max", nums(x2, y2, z2));
        return o;
    }
}
