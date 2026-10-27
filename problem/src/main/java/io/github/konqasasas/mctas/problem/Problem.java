package io.github.konqasasas.mctas.problem;

import io.github.konqasasas.mctas.physics.SparseBlockWorld;

public final class Problem {
    public static final String FORMAT = "mctas-problem";
    public static final int VERSION = 1;
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

    public Problem(String id, String title, SparseBlockWorld world, Spawn spawn, Region start, Region goal,
                   int maxTicks, double voidY, Target target) {
        this.id = id;
        this.title = title;
        this.world = world;
        this.spawn = spawn;
        this.start = start;
        this.goal = goal;
        this.maxTicks = maxTicks;
        this.voidY = voidY;
        this.target = target;
    }
}
