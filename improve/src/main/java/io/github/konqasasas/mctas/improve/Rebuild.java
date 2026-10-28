package io.github.konqasasas.mctas.improve;

import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.technique.Exit;
import io.github.konqasasas.mctas.technique.SegmentSearch;
import io.github.konqasasas.mctas.technique.Technique;

import java.util.List;
import java.util.Random;

/**
 * The large-neighbourhood move: throw away the inputs of one stretch of the run and rebuild it with
 * the exact local search ({@link SegmentSearch}, WS8), then keep the rest of the run as it was.
 *
 * <p>The stretch runs from tick {@code a} to an anchor tick {@code b} of the current run. The
 * search goes from the state at {@code a} to the plane through the anchor's position across the
 * direction from {@code a} to {@code b}, at the anchor's height (standing, if the anchor stands).
 * Each way it finds is spliced in front of the old inputs from {@code b} on, and the whole run is
 * judged again. When the old inputs no longer reach the goal from the new state, one repair
 * rebuilds the next stretch too. Near the goal the stretch can end at the goal itself.
 */
final class Rebuild {
    /** Largest beam width and window length. */
    int maxWidth = 8000;
    int maxWindow = 80;
    /** Milliseconds per search per 1000 of beam width. */
    long millisPer1000 = 1500;
    /**
     * Chance of a deep rebuild: the full set of view offsets and {@link #deepWidth}, as wide as the
     * WS8 searches that beat the human on stpt1 (F-029). Slow (tens of seconds), so rare.
     */
    double deepChance = 0.1;
    int deepWidth = 20000;
    /** {@code System.nanoTime()} after which no search may run; 0 for none. */
    long deadlineNanos;

    private static final float[][] OFFSET_SETS = {
            {0, 10, -10, 45, -45},
            {0, 4, -4, 22.5F, -22.5F, 90, -90},
            {0, 4, -4, 10, -10, 22.5F, -22.5F, 45, -45, 90, -90},
            {0},
    };

    Run apply(Course c, Run cur, Random rnd) {
        int goal = cur.goalTick;
        int lo = Math.max(0, cur.startTick - 12);
        if (goal - lo < 4) return null;
        int a = lo + rnd.nextInt(goal - lo - 2);
        boolean toGoal = goal - a <= 100 && rnd.nextDouble() < 0.3;
        boolean deep = rnd.nextDouble() < deepChance;
        int len = toGoal ? goal - a : Math.min(goal - a, deep ? 15 + rnd.nextInt(46)
                : 6 + (int) (Math.pow(rnd.nextDouble(), 1.5) * (maxWindow - 6)));
        int b = a + len;
        SegmentSearch.Options o = options(c, rnd, deep);
        o.maxTicks = len + 1;
        PlayerState entry = cur.states[a];
        Exit exit;
        float heading;
        if (toGoal || b >= goal) {
            toGoal = true;
            b = goal;
            exit = Exit.box(c.problem.goal.minX, c.problem.goal.minY, c.problem.goal.minZ, c.problem.goal.maxX,
                    c.problem.goal.maxY, c.problem.goal.maxZ);
            heading = Technique.headingTo(entry, (c.problem.goal.minX + c.problem.goal.maxX) / 2,
                    (c.problem.goal.minZ + c.problem.goal.maxZ) / 2);
        } else {
            heading = heading(entry, cur.states[b]);
            exit = anchor(cur.states[b], heading, SIDES[rnd.nextInt(SIDES.length)]);
        }
        List<SegmentSearch.Result> rs = new SegmentSearch().search(c.world, entry, cur.lastToggle[a] - a, exit, heading, o);
        if (!toGoal) sortByAnchor(rs, cur.states[b]);
        Run best = null;
        int tried = 0;
        if (DEBUG) {
            System.err.printf("rebuild a=%d len=%d deep=%b goal=%b width=%d offsets=%d: %d results%s%n", a, len, deep, toGoal,
                    o.width, o.offsets.length, rs.size(), rs.isEmpty() ? "" : " fastest " + rs.get(0).ticks());
        }
        for (SegmentSearch.Result r : rs) {
            if (tried++ >= 12) break;
            PlayerInput[] in = Edits.splice(cur.inputs, a, r.inputs, cur.inputs, toGoal ? cur.inputs.length : b);
            Run run = c.evaluate(cur, a, in);
            if (!run.valid && !toGoal && tried <= 3) run = repair(c, cur, a, b, r, run, rnd);
            if (DEBUG) System.err.println("  splice " + r.ticks() + " ticks: " + run);
            if (run != null && run.betterThan(best)) best = run;
        }
        return best;
    }

    static final boolean DEBUG = System.getenv("MCTAS_IMPROVE_DEBUG") != null;

    /**
     * The spliced run failed after the anchor: rebuild from the new anchor state to a later anchor of
     * the old run, then the old inputs from there.
     */
    private Run repair(Course c, Run cur, int a, int b, SegmentSearch.Result r, Run failed, Random rnd) {
        int goal = cur.goalTick;
        int len2 = Math.min(goal - b, 10 + rnd.nextInt(30));
        if (len2 <= 0) return failed;
        int c2 = b + len2;
        PlayerState from = r.end;
        int at = a + r.ticks();
        SegmentSearch.Options o = options(c, rnd, false);
        o.width = Math.min(o.width, 3000);
        o.maxTicks = len2 + 2;
        Exit exit;
        float heading;
        boolean toGoal = c2 >= goal;
        if (toGoal) {
            exit = Exit.box(c.problem.goal.minX, c.problem.goal.minY, c.problem.goal.minZ, c.problem.goal.maxX,
                    c.problem.goal.maxY, c.problem.goal.maxZ);
            heading = Technique.headingTo(from, (c.problem.goal.minX + c.problem.goal.maxX) / 2,
                    (c.problem.goal.minZ + c.problem.goal.maxZ) / 2);
        } else {
            heading = heading(from, cur.states[c2]);
            exit = anchor(cur.states[c2], heading, SIDES[rnd.nextInt(SIDES.length)]);
        }
        int lastToggle = failed.lastToggle.length > at ? failed.lastToggle[at] - at : r.lastToggle - r.ticks();
        List<SegmentSearch.Result> rs = new SegmentSearch().search(c.world, from, lastToggle, exit, heading, o);
        if (!toGoal) sortByAnchor(rs, cur.states[c2]);
        Run best = failed;
        int tried = 0;
        for (SegmentSearch.Result r2 : rs) {
            if (tried++ >= 6) break;
            java.util.List<PlayerInput> mid = new java.util.ArrayList<PlayerInput>(r.inputs);
            mid.addAll(r2.inputs);
            PlayerInput[] in = Edits.splice(cur.inputs, a, mid, cur.inputs, toGoal ? cur.inputs.length : c2);
            Run run = c.evaluate(failed, at, in);
            if (run.betterThan(best)) best = run;
        }
        return best;
    }

    private SegmentSearch.Options options(Course c, Random rnd, boolean deep) {
        SegmentSearch.Options o = new SegmentSearch.Options();
        o.width = deep ? deepWidth : (int) Math.round(300 * Math.pow(maxWidth / 300.0, rnd.nextDouble()));
        o.bucketCap = Math.max(40, o.width / 15);
        o.offsets = deep ? OFFSET_SETS[2] : OFFSET_SETS[rnd.nextInt(OFFSET_SETS.length)];
        o.voidY = c.problem.voidY;
        o.results = 16;
        o.extraTicks = 1;
        o.deadlineNanos = System.nanoTime() + Math.max(300, millisPer1000 * o.width / 1000) * 1_000_000L;
        if (deadlineNanos != 0) o.deadlineNanos = Math.min(o.deadlineNanos, deadlineNanos);
        return o;
    }

    static float heading(PlayerState from, PlayerState to) {
        double dx = to.posX - from.posX, dz = to.posZ - from.posZ;
        if (dx * dx + dz * dz < 0.25) {
            double vx = to.motionX, vz = to.motionZ;
            if (vx * vx + vz * vz < 1e-6) return to.rotationYaw;
            return (float) Math.toDegrees(Math.atan2(-vx, vz));
        }
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** The plane through the anchor's position across the heading, at its height, standing if it stands. */
    /**
     * The plane through the anchor's position across the heading, at its height, standing if it
     * stands, and within {@code side} blocks of the anchor sideways (and, in the air, with a vertical
     * speed within 0.1 of it), so the old inputs after the anchor still fit the new state.
     */
    static Exit anchor(PlayerState s, float heading, double side) {
        Exit plane = Exit.plane(s.posX, s.posZ, heading, 0, s.posY - (s.onGround ? 1e-6 : 0.25), s.onGround);
        double r = Math.toRadians(heading);
        double dx = -Math.sin(r), dz = Math.cos(r);
        return (before, after) -> plane.reached(before, after)
                && Math.abs((after.posX - s.posX) * dz - (after.posZ - s.posZ) * dx) <= side
                && (s.onGround || Math.abs(after.motionY - s.motionY) < 0.1);
    }

    private static final double[] SIDES = {0.1, 0.3, 1.0, Double.POSITIVE_INFINITY};

    /** Results of equal ticks ordered by how close their end is to the anchor state. */
    static void sortByAnchor(List<SegmentSearch.Result> rs, PlayerState anchor) {
        rs.sort((p, q) -> p.ticks() != q.ticks() ? Integer.compare(p.ticks(), q.ticks())
                : Double.compare(closeness(p.end, anchor), closeness(q.end, anchor)));
    }

    private static double closeness(PlayerState p, PlayerState q) {
        double dx = p.posX - q.posX, dy = p.posY - q.posY, dz = p.posZ - q.posZ;
        double vx = p.motionX - q.motionX, vy = p.motionY - q.motionY, vz = p.motionZ - q.motionZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) + 5 * Math.sqrt(vx * vx + vy * vy + vz * vz)
                + (p.onGround != q.onGround || p.sprinting != q.sprinting ? 10 : 0);
    }
}
