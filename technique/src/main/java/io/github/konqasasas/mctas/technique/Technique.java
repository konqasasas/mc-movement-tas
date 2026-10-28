package io.github.konqasasas.mctas.technique;

import io.github.konqasasas.mctas.physics.BlockWorld;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The macro-action entry point for planners: the fastest ways from a state onto a surface, found with
 * {@link SegmentSearch} under a time budget.
 *
 * <p>The search heading points from the entry to the target's centre. It runs with beam widths 500,
 * 1000, 2000 and so on until the budget is spent, and merges the results. The answer is a Pareto set
 * over (ticks, exit speed): sorted by ticks, each segment leaves faster (horizontal speed) than every
 * faster segment before it, since a planner that continues from the exit may prefer one tick more for
 * a faster exit.
 */
public final class Technique {
    private Technique() {
    }

    /** One way onto the target. */
    public static final class Segment {
        public final List<PlayerInput> inputs;
        /** The state on the first tick standing on the target. */
        public final PlayerState exit;
        /** Ticks since the last sprint or sneak change, at the exit (for the next segment's 3-tick rule). */
        public final int ticksSinceLastToggle;

        Segment(List<PlayerInput> inputs, PlayerState exit, int ticksSinceLastToggle) {
            this.inputs = Collections.unmodifiableList(inputs);
            this.exit = exit;
            this.ticksSinceLastToggle = ticksSinceLastToggle;
        }

        public int ticks() {
            return inputs.size();
        }

        public double exitSpeed() {
            return Math.sqrt(exit.motionX * exit.motionX + exit.motionZ * exit.motionZ);
        }
    }

    /** Ways onto {@code target} from {@code entry} with the default options. */
    public static List<Segment> solve(BlockWorld world, PlayerState entry, int ticksSinceLastToggle, Target target,
                                      long budgetMillis) {
        return solve(world, entry, ticksSinceLastToggle, Exit.landing(target), headingTo(entry, target.centerX(),
                target.centerZ()), budgetMillis, new SegmentSearch.Options());
    }

    /**
     * Ways to {@code exit} from {@code entry}, searching around {@code headingYaw}. {@code base} gives
     * everything but the width and the deadline.
     */
    public static List<Segment> solve(BlockWorld world, PlayerState entry, int ticksSinceLastToggle, Exit exit,
                                      float headingYaw, long budgetMillis, SegmentSearch.Options base) {
        long deadline = System.nanoTime() + budgetMillis * 1_000_000L;
        List<SegmentSearch.Result> all = new ArrayList<SegmentSearch.Result>();
        SegmentSearch search = new SegmentSearch();
        for (int width = 500; ; width *= 2) {
            SegmentSearch.Options o = copy(base);
            o.width = width;
            o.bucketCap = Math.max(50, width / 20);
            o.deadlineNanos = deadline;
            List<SegmentSearch.Result> rs = search.search(world, entry, -ticksSinceLastToggle, exit, headingYaw, o);
            all.addAll(rs);
            if (search.timedOut || System.nanoTime() > deadline || width >= base.width) break;
        }
        return pareto(all);
    }

    static List<Segment> pareto(List<SegmentSearch.Result> rs) {
        List<Segment> segs = new ArrayList<Segment>();
        for (SegmentSearch.Result r : rs) segs.add(new Segment(r.inputs, r.end, r.ticks() - r.lastToggle));
        Collections.sort(segs, (p, q) -> p.ticks() != q.ticks() ? Integer.compare(p.ticks(), q.ticks())
                : Double.compare(q.exitSpeed(), p.exitSpeed()));
        List<Segment> out = new ArrayList<Segment>();
        double best = -1;
        for (Segment s : segs) {
            if (s.exitSpeed() > best + 1e-9) {
                out.add(s);
                best = s.exitSpeed();
            }
        }
        return out;
    }

    /** The yaw that faces from the state's position towards (x, z). */
    public static float headingTo(PlayerState s, double x, double z) {
        return (float) Math.toDegrees(Math.atan2(-(x - s.posX), z - s.posZ));
    }

    private static SegmentSearch.Options copy(SegmentSearch.Options b) {
        SegmentSearch.Options o = new SegmentSearch.Options();
        o.width = b.width;
        o.bucketCap = b.bucketCap;
        o.maxTicks = b.maxTicks;
        o.extraTicks = b.extraTicks;
        o.lookahead = b.lookahead;
        o.offsets = b.offsets;
        o.sneak = b.sneak;
        o.voidY = b.voidY;
        o.results = b.results;
        o.cells = b.cells;
        return o;
    }
}
