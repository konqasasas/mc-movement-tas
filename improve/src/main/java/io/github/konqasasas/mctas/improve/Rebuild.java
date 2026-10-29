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
    /** Mend splices that break the toggle rule further on ({@link ToggleFix}). */
    boolean fixToggles = true;
    private final ToggleFix toggleFix = new ToggleFix();
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
        if (focusChance > 0 && rnd.nextDouble() < focusChance) {
            int[] spots = slowSpots(cur, lo, goal - 2);
            if (spots.length > 0) a = Math.max(lo, spots[rnd.nextInt(spots.length)] - rnd.nextInt(11));
        }
        // The run whose inputs and states come before a: cur, or cur with a landing made a jump.
        Run head = cur;
        if (landChance > 0 && rnd.nextDouble() < landChance) {
            int[] ls = shortLandings(cur, lo, goal - 8);
            if (ls.length > 0) {
                int land = ls[rnd.nextInt(ls.length)];
                Run h = jumpOnLanding(c, cur, land, rnd);
                if (h != null) {
                    head = h;
                    a = land + 1;
                }
            }
        }
        boolean toGoal = goal - a <= 100 && rnd.nextDouble() < 0.3;
        boolean deep = rnd.nextDouble() < deepChance;
        int len = toGoal ? goal - a : Math.min(goal - a, deep ? 15 + rnd.nextInt(46)
                : 6 + (int) (Math.pow(rnd.nextDouble(), 1.5) * (maxWindow - 6)));
        if (head != cur) {
            // After the early jump the run is in another phase of its jumps, so end on a later
            // landing of the old run (a standing state) rather than in the air.
            int skip = rnd.nextInt(3);
            for (int t = a + 4; t < Math.min(goal - 1, a + maxWindow); t++) {
                if (cur.states[t].onGround && !cur.states[t - 1].onGround && skip-- == 0) {
                    len = t - a;
                    toGoal = false;
                    break;
                }
            }
        }
        int b = a + len;
        SegmentSearch.Options o = options(c, rnd, deep);
        o.maxTicks = len + 1;
        o.extraActions = windowInputs(cur.inputs, a, b);
        budget(o, len);
        PlayerState entry = head.states[a];
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
            // After an early jump the speed differs from the old run's, so only the plane counts.
            double side = head != cur ? SIDES[SIDES.length - 1] : SIDES[rnd.nextInt(SIDES.length)];
            exit = anchor(cur.states[b], heading, side);
        }
        SegmentSearch ss = new SegmentSearch();
        List<SegmentSearch.Result> rs = ss.search(c.world, entry, head.lastToggle[a] - a, exit, heading, o);
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
            PlayerInput[] in = Edits.splice(head.inputs, a, r.inputs, cur.inputs, toGoal ? cur.inputs.length : b);
            Run run = c.evaluate(head, a, in);
            Run spliced = run;
            if (fixToggles) run = toggleFix.apply(c, run, rnd);
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

    /**
     * Chance that a rebuild starts at a slow spot ({@link #slowSpots}) instead of anywhere. The human
     * gap analysis (WS11) found the AI runs lose ticks where they run on the ground without jumping
     * and where a jump hits a wall.
     */
    double focusChance = 0;
    /** Ground ticks in a row without a jump that count as running without jumping. */
    static final int FLAT_RUN = 6;

    /** Ticks in {@code [lo, hi)} that start a stretch of {@link #FLAT_RUN} ground ticks without a jump, or hit a wall in the air. */
    static int[] slowSpots(Run run, int lo, int hi) {
        int[] out = new int[Math.max(0, hi - lo)];
        int n = 0;
        int flat = 0;
        for (int t = Math.max(1, lo); t < hi && t < run.states.length; t++) {
            PlayerState s = run.states[t];
            boolean jump = t < run.inputs.length && run.inputs[t].has(PlayerInput.JUMP);
            flat = s.onGround && !jump ? flat + 1 : 0;
            if (flat == FLAT_RUN || (!s.onGround && s.collidedHorizontally)) out[n++] = t - (flat == FLAT_RUN ? FLAT_RUN - 1 : 0);
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /**
     * Chance that a rebuild first makes a short landing a jump ({@link #jumpOnLanding}) and rebuilds
     * from the tick after it. WS14 (F-1007): the AI runs lose most of their ticks to landings that stay
     * on the ground for 2 to 4 ticks before the next jump; the human jumps on the landing tick.
     */
    double landChance = 0;

    /** The first ground tick of each landing in {@code [lo, hi)} that stays on the ground for 2 to 4 ticks. */
    static int[] shortLandings(Run run, int lo, int hi) {
        int[] out = new int[Math.max(0, hi - lo)];
        int n = 0;
        for (int t = Math.max(1, lo); t < hi && t < run.length; t++) {
            if (!run.states[t].onGround || run.states[t - 1].onGround) continue;
            int g = groundTicks(run, t);
            if (g >= 2 && g <= 4) out[n++] = t;
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /** Ground ticks in a row from the landing at {@code t}. */
    static int groundTicks(Run run, int t) {
        int g = 0;
        while (t + g <= run.length && run.states[t + g].onGround) g++;
        return g;
    }

    /**
     * {@code run} with the take-off after the landing at {@code land} moved onto the landing tick:
     * the input at the landing gets the take-off's keys and view (plus jump), and half the time the
     * view turns to the take-off's direction a few ticks before, in the air. The rest of the inputs
     * stay; the caller rebuilds from the tick after the jump. Null when the run breaks before then.
     */
    static Run jumpOnLanding(Course c, Run run, int land, Random rnd) {
        int g = groundTicks(run, land);
        PlayerInput take = run.inputs[land + g - 1];
        PlayerInput[] in = run.inputs.clone();
        int from = land;
        if (rnd.nextBoolean()) {
            from = Math.max(0, land - 1 - rnd.nextInt(6));
            for (int i = from; i < land; i++) in[i] = Edits.withYaw(in[i], take.yaw);
        }
        in[land] = new PlayerInput(take.keys | PlayerInput.JUMP, take.yaw, take.pitch, take.slot);
        Run h = c.evaluate(run, from, in);
        if (h.length < land + 1 || h.states[land + 1].onGround) return null;
        return h;
    }

    /** Start repairs where the run drifts from the old one, not where it fails ({@link #drift}). */
    boolean useDrift = true;
    /** How far (blocks) the run may be from the old run's state {@code shift} ticks on before it counts as drifted. */
    static final double DRIFT = 1.0;

    /**
     * The first tick after {@code joint} where {@code run} is more than {@link #DRIFT} from the old run
     * {@code shift} ticks later, or {@code run.length}. A run that wanders off and ends without the
     * goal fails only when its inputs run out, long after it went wrong.
     */
    static int drift(Run run, Run old, int joint, int shift) {
        for (int t = joint; t <= run.length && t + shift < old.states.length && t + shift <= old.goalTick; t++) {
            PlayerState a = run.states[t], b = old.states[t + shift];
            double dx = a.posX - b.posX, dy = a.posY - b.posY, dz = a.posZ - b.posZ;
            if (dx * dx + dy * dy + dz * dz > DRIFT * DRIFT) return t;
        }
        return run.length;
    }

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
            int f = useDrift ? Math.min(run.length, drift(run, cur, joint, shift)) : run.length;
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
                if (fixToggles) x = toggleFix.apply(c, x, rnd);
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
