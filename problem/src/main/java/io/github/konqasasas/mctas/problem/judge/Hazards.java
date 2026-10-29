package io.github.konqasasas.mctas.problem.judge;

import io.github.konqasasas.mctas.physics.Aabb;
import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.BlockWorld;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;

/**
 * D-005: a run must not take damage or trample farmland. The engine does not model either, so this
 * looks at each state after a tick, as 1.12 does in {@code Entity.move}:
 * <ul>
 *   <li>fire, lava and cactus hurt when the player's box, shrunk by 0.001, overlaps their block
 *       ({@code isFlammableWithin}, {@code doBlockCollisions}); a cactus's box is smaller than its
 *       block, so touching its side counts;</li>
 *   <li>a magma block hurts the player standing on it without sneaking ({@code onEntityWalk}, on the
 *       block under {@code posY - 0.2});</li>
 *   <li>farmland turns to dirt when the player lands on it after falling more than 0.5
 *       ({@code onFallenUpon}: a chance of {@code fallDistance - 0.5}).</li>
 * </ul>
 * Fall damage is off on the courses, so falls only matter for farmland. (The wither rose and sweet
 * berry bush come after 1.12; their names are listed so that a course written with them is not
 * passed.)
 */
public final class Hazards {
    private Hazards() {
    }

    /** Blocks that hurt while the box overlaps them. */
    public static boolean hurtsInside(BlockType t) {
        return is(t, "minecraft:fire") || is(t, "minecraft:lava") || is(t, "minecraft:flowing_lava") || is(t, "minecraft:cactus")
            || is(t, "minecraft:wither_rose") || is(t, "minecraft:sweet_berry_bush");
    }

    /** Blocks that hurt while standing on them without sneaking. */
    public static boolean hurtsUnderfoot(BlockType t) {
        return is(t, "minecraft:magma") || is(t, "minecraft:magma_block");
    }

    /** Blocks that a landing can trample. */
    public static boolean trampled(BlockType t) {
        return is(t, "minecraft:farmland");
    }

    /** Whether the world has any block that can hurt or be trampled (when not, no state needs checking). */
    public static boolean present(SparseBlockWorld world) {
        boolean[] any = {false};
        world.forEach((x, y, z, b) -> {
            if (hurtsInside(b) || hurtsUnderfoot(b) || trampled(b)) any[0] = true;
        });
        return any[0];
    }

    /** Whether the player takes damage in this state (after a tick). */
    public static boolean hurts(BlockWorld world, PlayerState s) {
        if (s.onGround && !s.isSneaking() && hurtsUnderfoot(underfoot(world, s))) return true;
        Aabb b = s.box;
        int x0 = floor(b.minX + 0.001), x1 = floor(b.maxX - 0.001);
        int y0 = floor(b.minY + 0.001), y1 = floor(b.maxY - 0.001);
        int z0 = floor(b.minZ + 0.001), z1 = floor(b.maxZ - 0.001);
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    if (hurtsInside(world.getBlock(x, y, z))) return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether the tick that ended in {@code after} tramples farmland: it ends on the ground on
     * farmland, and the player had fallen {@code fallBefore} (the fall distance at the start of the
     * tick, which the landing tick hands to {@code onFallenUpon}) more than 0.5.
     */
    public static boolean tramples(BlockWorld world, float fallBefore, PlayerState after) {
        return after.onGround && fallBefore > 0.5F && trampled(underfoot(world, after));
    }

    /** What the tick from {@code before} to {@code after} did against D-005, or null: "damage" or "trampling". */
    public static String check(BlockWorld world, PlayerState before, PlayerState after) {
        if (hurts(world, after)) return "damage";
        if (tramples(world, before.fallDistance, after)) return "trampling";
        return null;
    }

    /** The block the player stands on: the one under {@code posY - 0.2}. */
    private static BlockType underfoot(BlockWorld world, PlayerState s) {
        return world.getBlock(floor(s.posX), floor(s.posY - 0.20000000298023224D), floor(s.posZ));
    }

    /** Block names carry their properties ({@code minecraft:lava[level=0]}). */
    private static boolean is(BlockType t, String id) {
        String n = t.name();
        return n.startsWith(id) && (n.length() == id.length() || n.charAt(id.length()) == '[');
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
