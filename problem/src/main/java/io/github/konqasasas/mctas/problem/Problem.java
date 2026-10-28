package io.github.konqasasas.mctas.problem;

import io.github.konqasasas.mctas.physics.Hotbar;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;

public final class Problem {
    public static final String FORMAT = "mctas-problem";
    /** The newest version; version 1 files (no {@code player}) are still read. */
    public static final int VERSION = 2;
    public static final String MC_VERSION = "1.12.2";

    public final String id;
    public final String title;
    public final SparseBlockWorld world;
    public final Spawn spawn;
    public final Region start;
    public final Region goal;
    public final int maxTicks;
    public final double voidY;
    public final Target target;
    /** The blocks the player starts with in the hotbar; empty unless the problem allows placing. */
    public final Hotbar inventory;
    /** The hotbar slot held at the spawn, 0 to 8. */
    public final int selectedSlot;

    public Problem(String id, String title, SparseBlockWorld world, Spawn spawn, Region start, Region goal,
                   int maxTicks, double voidY, Target target) {
        this(id, title, world, spawn, start, goal, maxTicks, voidY, target, Hotbar.EMPTY, 0);
    }

    public Problem(String id, String title, SparseBlockWorld world, Spawn spawn, Region start, Region goal,
                   int maxTicks, double voidY, Target target, Hotbar inventory, int selectedSlot) {
        this.id = id;
        this.title = title;
        this.world = world;
        this.spawn = spawn;
        this.start = start;
        this.goal = goal;
        this.maxTicks = maxTicks;
        this.voidY = voidY;
        this.target = target;
        this.inventory = inventory;
        this.selectedSlot = selectedSlot;
    }
}
