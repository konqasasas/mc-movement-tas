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
        o.extraActions = windowInputs(cur.inputs, a, b);
        budget(o, len);
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
        SegmentSearch ss = new SegmentSearch();
        List<SegmentSearch.Result> rs = ss.search(c.world, entry, cur.lastToggle[a] - a, exit, heading, o);
        if (!toGoal) sortByAnchor(rs, cur.states[b]);
        boolean faster = !rs.isEmpty() && rs.get(0).ticks() < len;
        boolean anyValid = false;
        Run best = null;
        int tried = 0;
        StringBuilder log = DEBUG ? new StringBuilder() : null;
        if (DEBUG) {
            log.append(String.format("rebuild a=%d len=%d deep=%b goal=%b width=%d offsets=%d: %d results%s%n", a, len, deep, toGoal,
                    o.width, o.offsets.length, rs.size(), rs.isEmpty() ? "" : " fastest " + rs.get(0).ticks()));
        }
        for (SegmentSearch.Result r : rs) {
            if (tried++ >= 12) break;
            PlayerInput[] in = Edits.splice(cur.inputs, a, r.inputs, cur.inputs, toGoal ? cur.inputs.length : b);
            Run run = c.evaluate(cur, a, in);
            Run spliced = run;
            if (!run.valid && !toGoal && tried <= 3) run = repair(c, cur, run, a + r.ticks(), len - r.ticks(), rnd);
            if (DEBUG) log.append("  splice ").append(r.ticks()).append(" ticks, joint ").append(a + r.ticks())
                    .append(": ").append(spliced).append(run != spliced ? "; repaired: " + run : "").append('\n');
            if (run != null && run.valid && r.ticks() < len) anyValid = true;
            if (run != null && run.betterThan(best)) best = run;
        }
        Stage st = rs.isEmpty() ? (ss.timedOut ? Stage.TIMED_OUT : Stage.NO_WAY) : !faster ? Stage.NOT_FASTER
                : !anyValid ? Stage.SPLICE_FAILS : best != null && best.betterThan(cur) ? Stage.IMPROVED : Stage.NOT_BETTER;
        STAGES[st.ordinal()].incrementAndGet();
        if (DEBUG) System.err.print(log.append("  -> ").append(st).append('\n'));
        return best;
    }

    /** What became of a rebuild, for {@code MCTAS_IMPROVE_DEBUG}. */
    enum Stage { TIMED_OUT, NO_WAY, NOT_FASTER, SPLICE_FAILS, NOT_BETTER, IMPROVED }

    static final java.util.concurrent.atomic.AtomicLong[] STAGES = new java.util.concurrent.atomic.AtomicLong[Stage.values().length];

    static {
        for (int i = 0; i < STAGES.length; i++) STAGES[i] = new java.util.concurrent.atomic.AtomicLong();
    }

    static String stages() {
        StringBuilder sb = new StringBuilder();
        for (Stage st : Stage.values()) sb.append(' ').append(st.name().toLowerCase()).append(' ').append(STAGES[st.ordinal()].get());
        return sb.toString().trim();
    }

    static final boolean DEBUG = System.getenv("MCTAS_IMPROVE_DEBUG") != null;

    /** Most repair rounds after one splice. */
    int maxRepairs = 4;

    /**
     * The spliced run is {@code shift} ticks ahead of the old run after tick {@code joint}, but the old
     * inputs drift away from it and it fails later (often a hundred ticks on). Each round goes back a
     * few ticks before the failure, rebuilds from there to an anchor of the old run past it (keeping
     * most of the lead), and goes on with the old inputs, until the run finishes or a round finds no
     * way. Among failing runs the one that gets furthest is kept.
     */
    private Run repair(Course c, Run cur, Run failed, int joint, int shift, Random rnd) {
        Run run = failed;
        int goal = cur.goalTick;
        for (int round = 0; round < maxRepairs && !run.valid; round++) {
            int f = run.length;
            int s = Math.max(joint, f - (5 + rnd.nextInt(26)));
            if (s >= run.states.length) break;
            int tO = s + shift + 15 + rnd.nextInt(26);
            boolean toGoal = tO >= goal;
            PlayerState from = run.states[s];
            SegmentSearch.Options o = options(c, rnd, false);
            o.width = Math.min(o.width, 3000);
            o.maxTicks = (toGoal ? goal : tO) - shift - s + 3;
            if (o.maxTicks < 2) break;
            List<PlayerInput> extra = windowInputs(cur.inputs, s + shift, toGoal ? goal : tO);
            for (PlayerInput x : windowInputs(run.inputs, s, f)) if (!extra.contains(x) && extra.size() < 2 * MAX_EXTRA) extra.add(x);
            o.extraActions = extra;
            budget(o, o.maxTicks);
            Exit exit;
            float heading;
            if (toGoal) {
                exit = Exit.box(c.problem.goal.minX, c.problem.goal.minY, c.problem.goal.minZ, c.problem.goal.maxX,
                        c.problem.goal.maxY, c.problem.goal.maxZ);
                heading = Technique.headingTo(from, (c.problem.goal.minX + c.problem.goal.maxX) / 2,
                        (c.problem.goal.minZ + c.problem.goal.maxZ) / 2);
            } else {
                heading = heading(from, cur.states[tO]);
                exit = anchor(cur.states[tO], heading, SIDES[rnd.nextInt(SIDES.length)]);
            }
            List<SegmentSearch.Result> rs = new SegmentSearch().search(c.world, from, run.lastToggle[s] - s, exit, heading, o);
            if (!toGoal) sortByAnchor(rs, cur.states[tO]);
            Run next = null;
            int nextShift = shift, nextJoint = joint;
            int tried = 0;
            for (SegmentSearch.Result r : rs) {
                if (tried++ >= 6) break;
                PlayerInput[] in = Edits.splice(run.inputs, s, r.inputs, cur.inputs, toGoal ? cur.inputs.length : tO);
                Run x = c.evaluate(run, s, in);
                boolean better = next == null || x.betterThan(next) || (!x.valid && !next.valid && x.length > next.length);
                if (better) {
                    next = x;
                    nextJoint = s + r.ticks();
                    nextShift = (toGoal ? goal : tO) - nextJoint;
                }
            }
            if (DEBUG) System.err.printf("    repair round %d: fail %d, from %d to old %d (shift %d): %d results -> %s%n", round, f, s,
                    tO, shift, rs.size(), next == null ? "none" : next.valid ? next.toString() : "fails at " + next.length);
            if (next == null) break;
            run = next;
            joint = nextJoint;
            shift = nextShift;
        }
        return run;
    }

    private SegmentSearch.Options options(Course c, Random rnd, boolean deep) {
        SegmentSearch.Options o = new SegmentSearch.Options();
        o.width = deep ? deepWidth : (int) Math.round(300 * Math.pow(maxWidth / 300.0, rnd.nextDouble()));
        o.bucketCap = Math.max(40, o.width / 15);
        o.offsets = deep ? OFFSET_SETS[2] : OFFSET_SETS[rnd.nextInt(OFFSET_SETS.length)];
        o.voidY = c.problem.voidY;
        o.results = 16;
        o.extraTicks = 1;
        return o;
    }

    /** Most inputs kept from a window ({@link #windowInputs}). */
    static final int MAX_EXTRA = 24;

    /**
     * The distinct inputs the run used from tick {@code a} to {@code b}, most used first, at most
     * {@link #MAX_EXTRA}. Added to the search's actions, they let it sneak, walk, back off or turn
     * the way the run did, which the default actions (always sprinting, set directions) cannot.
     */
    static List<PlayerInput> windowInputs(PlayerInput[] in, int a, int b) {
        java.util.Map<PlayerInput, Integer> n = new java.util.LinkedHashMap<PlayerInput, Integer>();
        for (int t = a; t < Math.min(b, in.length); t++) n.merge(in[t], 1, Integer::sum);
        List<PlayerInput> out = new java.util.ArrayList<PlayerInput>(n.keySet());
        out.sort((p, q) -> Integer.compare(n.get(q), n.get(p)));
        return out.size() > MAX_EXTRA ? new java.util.ArrayList<PlayerInput>(out.subList(0, MAX_EXTRA)) : out;
    }

    /**
     * Sets the search's deadline from its size: about {@link #ticksPerSecond} physics ticks per second,
     * twice the expected work, between 0.3 and 60 seconds, and never past {@link #deadlineNanos}.
     */
    void budget(SegmentSearch.Options o, int ticks) {
        int actions = o.offsets.length * 6 + 2 + (o.extraActions == null ? 0 : o.extraActions.size());
        double seconds = 2.0 * o.width * actions * (ticks + 1) / ticksPerSecond;
        long ms = Math.max(300, Math.min(60_000, (long) (seconds * 1000)));
        o.deadlineNanos = System.nanoTime() + ms * 1_000_000L;
        if (deadlineNanos != 0) o.deadlineNanos = Math.min(o.deadlineNanos, deadlineNanos);
    }

    /** Physics ticks per second one search thread runs at (F-301, with the other threads busy). */
    double ticksPerSecond = 400_000;

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
     * speed within 0.1 of it), so the old inputs after the anchor still fit the new state. Unless
     * {@code side} is unlimited, the speed along the heading must be at least the anchor's (less 0.01)
     * and the sideways speed close to it: arriving slower is what makes the old jumps fall short later.
     */
    static Exit anchor(PlayerState s, float heading, double side) {
        Exit plane = Exit.plane(s.posX, s.posZ, heading, 0, s.posY - (s.onGround ? 1e-6 : 0.25), s.onGround);
        double r = Math.toRadians(heading);
        double dx = -Math.sin(r), dz = Math.cos(r);
        double speed = s.motionX * dx + s.motionZ * dz;
        double sideSpeed = s.motionX * dz - s.motionZ * dx;
        return (before, after) -> plane.reached(before, after)
                && Math.abs((after.posX - s.posX) * dz - (after.posZ - s.posZ) * dx) <= side
                && (s.onGround || Math.abs(after.motionY - s.motionY) < 0.1)
                && (side == Double.POSITIVE_INFINITY || after.motionX * dx + after.motionZ * dz >= speed - 0.01
                        && Math.abs(after.motionX * dz - after.motionZ * dx - sideSpeed) <= 0.05 + side / 4);
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
