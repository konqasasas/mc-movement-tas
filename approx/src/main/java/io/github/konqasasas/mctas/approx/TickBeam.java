package io.github.konqasasas.mctas.approx;

import io.github.konqasasas.mctas.physics.Physics;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.Region;
import io.github.konqasasas.mctas.problem.judge.ToggleRule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Tick-by-tick beam search guided by a {@link Route}.
 *
 * <p>Each tick, every node branches over a small set of inputs built around a heading: the direction
 * from the player to the route's next aim point, plus fixed offsets. On the ground the inputs are
 * strafe-running and three ways of jumping; in the air, strafe-running and two ways of braking.
 * Strafing is 45 degrees off the heading so the movement itself follows the heading. Children that
 * break the toggle rule, fall below the route or into the void are dropped; the rest are merged by a
 * coarse state key and ranked by the estimated finish time: ticks since the start entry plus the
 * remaining route distance at sprint speed, minus credit for speed already carried towards the aim.
 *
 * <p>Nodes that have not entered the start box yet compete only among themselves, so the run-up
 * keeps its own share of the beam and keeps feeding new start entries.
 */
public final class TickBeam {
    /** Heading offsets in degrees around the direction to the aim point. */
    static final float[] DEFAULT_OFFSETS = {0, 2, -2, 5, -5, 10, -10, 20, -20, 35, -35, 60, -60, 100, -100, 150, -150, 180};
    private static final double MISS_TICKS_PER_BLOCK = 40.0;
    /** A bound on horizontal speed in blocks per tick, for pruning nodes that cannot beat the best. */
    private static final double MAX_SPEED = 0.75;

    static final int RUN = PlayerInput.W | PlayerInput.A | PlayerInput.SPRINT;
    static final int JUMP_W = PlayerInput.W | PlayerInput.SPRINT | PlayerInput.JUMP;
    static final int JUMP_WA = PlayerInput.W | PlayerInput.A | PlayerInput.SPRINT | PlayerInput.JUMP;
    static final int JUMP_WD = PlayerInput.W | PlayerInput.D | PlayerInput.SPRINT | PlayerInput.JUMP;

    public static final class Node {
        final Node parent;
        final PlayerInput input;
        final PlayerState state;
        /** Tick of the last start entry, -1 before the first. */
        final int entry;
        final boolean inStart;
        final int stage;
        final int lastToggle;
        /** Ticks since the spawn. */
        final int tick;
        double score;

        Node(Node parent, PlayerInput input, PlayerState state, int entry, boolean inStart, int stage, int lastToggle) {
            this.tick = parent == null ? 0 : parent.tick + 1;
            this.parent = parent;
            this.input = input;
            this.state = state;
            this.entry = entry;
            this.inStart = inStart;
            this.stage = stage;
            this.lastToggle = lastToggle;
        }
    }

    /** The search result: the inputs from tick 1 up to the goal tick, and the ticks they score. */
    public static final class Result {
        public final List<PlayerInput> inputs;
        public final int ticks;

        Result(List<PlayerInput> inputs, int ticks) {
            this.inputs = inputs;
            this.ticks = ticks;
        }
    }

    static java.io.PrintStream DEBUG = System.getenv("APPROX_DEBUG") != null ? System.err : null;

    private final Problem problem;
    private final Route route;
    private final JumpTable jumps = JumpTable.get();
    private final Pilot pilot;
    private Node pilotBest;
    private int pilotBestTicks = Integer.MAX_VALUE;
    private final int width;
    private float[] offsets = DEFAULT_OFFSETS;
    private static final int RUN_UP_AFTER_FIRST_ENTRY = 40;
    private static final int PILOT_POOL = 3;
    private static final int PILOT_LANDINGS = 2;
    private static final int PILOT_TICKS = 120;
    private static final int PILOT_FAIL_TICKS = 100;
    private long deadline = Long.MAX_VALUE;

    public TickBeam(Problem problem, Route route, int width) {
        this.problem = problem;
        this.route = route;
        this.width = width;
        this.pilot = new Pilot(problem, route);
    }

    public TickBeam offsets(float[] offsets) {
        this.offsets = offsets;
        return this;
    }

    public TickBeam deadline(long epochMillis) {
        this.deadline = epochMillis;
        return this;
    }

    /** Searches from the spawn. */
    public Result run(int bestKnown) {
        PlayerState s0 = Rollout.spawn(problem);
        boolean in = problem.start.contains(s0.posX, s0.posY, s0.posZ);
        Node root = new Node(null, null, s0, in ? 0 : -1, in, 0, Integer.MIN_VALUE / 2);
        return timed(in ? Collections.singletonList(root) : runUp(root), bestKnown);
    }

    /** Searches onward from the state the given prefix of inputs reaches, which must be past the start entry. */
    public Result runFrom(List<PlayerInput> prefix, int bestKnown) {
        PlayerState s = Rollout.spawn(problem);
        boolean in = problem.start.contains(s.posX, s.posY, s.posZ);
        Node n = new Node(null, null, s, in ? 0 : -1, in, 0, Integer.MIN_VALUE / 2);
        for (PlayerInput a : prefix) {
            n = child(n, a, n.tick + 1);
            if (n == null) return null;
        }
        if (n.entry < 0) return null;
        n.score = estimate(n);
        return timed(Collections.singletonList(n), bestKnown);
    }

    Node childForDebug(Node n, PlayerInput a, int t) {
        return child(n, a, t);
    }

    boolean doomedForDebug(Node n) {
        return doomed(n);
    }

    /** Applies one input; null when the child is illegal (toggle rule, void). Does not check the goal. */
    private Node child(Node n, PlayerInput a, int t) {
        PlayerState ns = Physics.tick(problem.world, n.state, a);
        int lastToggle = n.lastToggle;
        if (ToggleRule.toggles(n.state, ns)) {
            if (!ToggleRule.allowed(lastToggle, t)) return null;
            lastToggle = t;
        }
        if (ns.posY < problem.voidY) return null;
        boolean in = problem.start.contains(ns.posX, ns.posY, ns.posZ);
        int entry = in && !n.inStart ? t : n.entry;
        int stage = n.stage;
        if (stage < route.waypoints.size() - 1 && route.landed(stage, ns.posX, ns.posY, ns.posZ, ns.onGround)) stage++;
        return new Node(n, a, ns, entry, in, stage, lastToggle);
    }

    /**
     * The run-up: a beam over ticks since the spawn whose nodes stop at their first start entry. The
     * entries, ranked by their estimate, seed {@link #timed}. Timing starts at the entry, so all of
     * them start the timed search together however long their run-up took.
     */
    private List<Node> runUp(Node root) {
        List<Node> beam = Collections.singletonList(root);
        Map<Long, Node> entries = new HashMap<Long, Node>();
        int firstEntry = -1;
        for (int t = 1; t <= problem.maxTicks && !beam.isEmpty(); t++) {
            if (System.currentTimeMillis() > deadline) break;
            List<List<Node>> expanded = beam.parallelStream().map(n -> expand(n, Integer.MAX_VALUE)).collect(Collectors.toList());
            Map<Long, Node> buckets = new HashMap<Long, Node>();
            for (List<Node> children : expanded) {
                for (Node c : children) {
                    Map<Long, Node> into = c.entry >= 0 ? entries : buckets;
                    long key = key(c);
                    Node old = into.get(key);
                    if (old == null || c.score < old.score) into.put(key, c);
                }
            }
            if (firstEntry < 0 && !entries.isEmpty()) firstEntry = t;
            beam = select(buckets.values());
            if (DEBUG != null && !beam.isEmpty()) DEBUG.println("runup t=" + t + " entries=" + entries.size() + " top " + describe(beam.get(0)));
            if (firstEntry >= 0 && t - firstEntry >= RUN_UP_AFTER_FIRST_ENTRY) break;
        }
        return select(entries.values());
    }

    /** The timed search: a beam over ticks since the start entry, from the given entries. */
    private Result timed(List<Node> entries, int bestKnown) {
        Region goal = problem.goal;
        List<Node> beam = entries;
        for (int e = 1; !beam.isEmpty() && e < bestKnown; e++) {
            if (System.currentTimeMillis() > deadline) break;
            final int limit = bestKnown;
            List<List<Node>> expanded = beam.parallelStream().map(n -> expand(n, limit)).collect(Collectors.toList());
            Map<Long, Node> buckets = new HashMap<Long, Node>();
            for (List<Node> children : expanded) {
                for (Node c : children) {
                    if (c.entry != c.parent.entry) continue; // left the start box and came back
                    PlayerState ns = c.state;
                    if (goal.contains(ns.posX, ns.posY, ns.posZ)) return better(result(c, e));
                    long key = key(c);
                    Node old = buckets.get(key);
                    if (old == null || c.score < old.score) buckets.put(key, c);
                }
            }
            beam = select(buckets.values());
            if (pilotBest != null && pilotBestTicks < bestKnown) bestKnown = pilotBestTicks;
            if (DEBUG != null && !beam.isEmpty()) DEBUG.println("e=" + e + " beam=" + beam.size() + " top " + describe(beam.get(0)));
        }
        return better(null);
    }

    /** The better of a goal found by the beam and the best finished pilot run. */
    private Result better(Result found) {
        if (pilotBest == null || (found != null && found.ticks <= pilotBestTicks)) return found;
        Pilot.Run r = pilot.play(pilotBest.state, pilotBest.tick, pilotBest.stage, pilotBest.entry, pilotBest.inStart,
            pilotBest.lastToggle, Integer.MAX_VALUE, problem.maxTicks - pilotBest.tick, true);
        Result prefix = result(pilotBest, 0);
        List<PlayerInput> all = new ArrayList<PlayerInput>(prefix.inputs);
        all.addAll(r.inputs);
        return new Result(all, pilotBestTicks);
    }

    /**
     * Keeps the width best nodes: the cheap estimate picks {@link #PILOT_POOL} times the width, and a
     * pilot rollout ({@link Pilot}) ranks those. A rollout that reaches the goal is a solution.
     */
    private List<Node> select(java.util.Collection<Node> nodes) {
        List<Node> pool = best(nodes, width * PILOT_POOL);
        pool.parallelStream().forEach(this::pilotScore);
        return best(pool, width);
    }

    private void pilotScore(Node n) {
        Pilot.Run r = pilot.play(n.state, n.tick, n.stage, n.entry, n.inStart, n.lastToggle, PILOT_LANDINGS,
            Math.min(PILOT_TICKS, problem.maxTicks - n.tick), false);
        int endTick = n.tick + r.ticks;
        if (r.finished) {
            int ticks = endTick - r.entry;
            n.score = ticks;
            synchronized (this) {
                if (ticks < pilotBestTicks) {
                    pilotBestTicks = ticks;
                    pilotBest = n;
                }
            }
            return;
        }
        Node end = new Node(null, null, r.end, r.entry, r.inStart, r.stage, r.lastToggle);
        double est = estimate(end);
        n.score = (r.entry >= 0 ? endTick - r.entry : 0) + est + (r.failed ? PILOT_FAIL_TICKS : 0);
    }

    private static List<Node> best(java.util.Collection<Node> nodes, int width) {
        List<Node> list = new ArrayList<Node>(nodes);
        list.sort((a, b) -> Double.compare(a.score, b.score));
        return list.size() > width ? new ArrayList<Node>(list.subList(0, width)) : list;
    }

    private static String describe(Node n) {
        return "stage=" + n.stage + " entry=" + n.entry + " tick=" + n.tick + " score=" + String.format("%.2f", n.score) + " " + n.state;
    }

    private static Result result(Node goalNode, int ticks) {
        List<PlayerInput> inputs = new ArrayList<PlayerInput>();
        for (Node n = goalNode; n.parent != null; n = n.parent) inputs.add(n.input);
        Collections.reverse(inputs);
        return new Result(inputs, ticks);
    }

    private List<Node> expand(Node n, int bestTicks) {
        int t = n.tick + 1;
        PlayerState s = n.state;
        double[] aim = new double[2];
        route.remaining(s.posX, s.posZ, n.stage, aim);
        double dx = aim[0] - s.posX, dz = aim[1] - s.posZ;
        float heading;
        if (dx * dx + dz * dz > 1e-6) {
            heading = (float) Math.toDegrees(Math.atan2(-dx, dz));
        } else if (s.motionX * s.motionX + s.motionZ * s.motionZ > 1e-8) {
            heading = (float) Math.toDegrees(Math.atan2(-s.motionX, s.motionZ));
        } else {
            heading = s.rotationYaw - 45F;
        }
        boolean canJump = (s.onGround && s.jumpTicks <= 1) || s.inWater;
        List<Node> out = new ArrayList<Node>(offsets.length * 4 + 2);
        for (float off : offsets) {
            float psi = heading + off;
            add(out, n, PlayerInput.of(RUN, psi + 45F), t, bestTicks);
            if (canJump) {
                add(out, n, PlayerInput.of(JUMP_W, psi), t, bestTicks);
                add(out, n, PlayerInput.of(JUMP_WA, psi + 45F), t, bestTicks);
                add(out, n, PlayerInput.of(JUMP_WD, psi - 45F), t, bestTicks);
            }
        }
        add(out, n, PlayerInput.of(0, heading), t, bestTicks);
        add(out, n, PlayerInput.of(PlayerInput.S, heading), t, bestTicks);
        return out;
    }

    private void add(List<Node> out, Node n, PlayerInput a, int t, int bestTicks) {
        Node c = child(n, a, t);
        if (c == null) return;
        PlayerState s = c.state;
        if (doomed(c)) {
            // Keep goal hits even below the route.
            if (!(c.entry >= 0 && problem.goal.contains(s.posX, s.posY, s.posZ))) return;
        }
        double est = estimate(c);
        if (c.entry >= 0) {
            int elapsed = c.tick - c.entry;
            if (elapsed + route.remaining(s.posX, s.posZ, c.stage, null) / MAX_SPEED >= bestTicks) return;
            c.score = elapsed + est;
        } else {
            c.score = est;
        }
        out.add(c);
    }

    /**
     * Ticks still needed to reach the goal, estimated. On the ground: the route distance at sprint
     * speed, or at the speed already carried towards the aim when that is faster. In the air: the flight is played
     * forward without collisions, steering towards the waypoint, until it comes down through the
     * waypoint's height; a landing on the waypoint continues from there along the route, and a miss
     * pays {@link #MISS_TICKS_PER_BLOCK} for each block it lands away from the waypoint.
     */
    double estimate(Node c) {
        PlayerState s = c.state;
        double[] aim = new double[2];
        if (s.onGround || s.inWater) return groundEstimate(s.posX, s.posY, s.posZ, s.motionX, s.motionZ, c.stage);
        Route.Waypoint w = route.waypoints.get(c.stage);
        Region g = problem.goal;
        double lowY = w.goal ? g.minY : w.top;
        double highY = w.goal ? g.maxY : w.top;
        // In the air the vertical motion ignores the keys, so the landing tick is fixed; the keys can
        // only move the landing point within a disc around where the drift alone would land.
        double x = s.posX, y = s.posY, z = s.posZ;
        double mx = s.motionX, my = s.motionY, mz = s.motionZ;
        double accel = s.jumpMovementFactor;
        double reach = 0, reachSpeed = 0;
        for (int k = 1; k <= 60; k++) {
            // The game moves vertically first, so a landing is decided by where the player was
            // before this tick's horizontal move; the position after it must still count for the goal.
            double ox = x, oz = z, oreach = reach;
            reachSpeed += accel;
            reach += reachSpeed;
            reachSpeed *= 0.91;
            double py = y;
            x += mx;
            y += my;
            z += mz;
            my = (my - 0.08) * 0.9800000190734863;
            mx *= 0.91;
            mz *= 0.91;
            if (w.goal && y >= lowY && y <= highY && route.missDistance(c.stage, x, z) <= reach && highY - lowY > 1e-9) return k;
            if (y >= py) continue;
            if (py >= lowY && y <= highY) {
                double miss = route.missDistance(c.stage, ox, oz) - oreach;
                if (w.goal) miss = Math.max(miss, route.missDistance(c.stage, x, z) - reach);
                if (miss <= 0) {
                    // Comes down onto the waypoint on this tick.
                    if (w.goal) return k;
                    return k + pushedEstimate(x, highY, z, c.stage + 1, reach, aim);
                }
            }
            Route.Surface under = route.support(ox, oz, y, py);
            if (under != null) {
                // Lands somewhere else first: carry on from there.
                return k + pushedEstimate(x, under.top, z, c.stage, reach, aim);
            }
            if (y < Math.min(lowY, route.floorY) - 2) {
                double miss = Math.max(0, route.missDistance(c.stage, x, z) - reach);
                return k + route.remaining(x, z, c.stage, null) / Route.RUN_SPEED + MISS_TICKS_PER_BLOCK * (miss + 0.1);
            }
        }
        double h = route.remaining(x, z, c.stage, null);
        return 60 + h / Route.RUN_SPEED + MISS_TICKS_PER_BLOCK * h;
    }

    /** {@link #groundEstimate} from the drift landing point after the keys have pushed it {@code reach} towards the aim. */
    private double pushedEstimate(double x, double y, double z, int stage, double reach, double[] aim) {
        if (stage >= route.waypoints.size()) return 0;
        route.remaining(x, z, stage, aim);
        double dx = aim[0] - x, dz = aim[1] - z;
        double d = Math.sqrt(dx * dx + dz * dz);
        double f = d <= reach ? 1 : reach / d;
        return groundEstimate(x + dx * f, y, z + dz * f, 0, 0, stage);
    }

    /**
     * Ticks from standing at (x, y, z) to the goal: the route distance at sprint speed, or at the
     * speed already carried towards the aim when that is faster. When the next waypoint needs a jump,
     * the jump's airtime is a floor: a jump lands no sooner however close the waypoint is.
     */
    double groundEstimate(double x, double y, double z, double mx, double mz, int stage) {
        double[] aim = new double[2];
        double h = route.remaining(x, z, stage, aim);
        double dx = aim[0] - x, dz = aim[1] - z;
        double d = Math.sqrt(dx * dx + dz * dz);
        double along = d > 1e-9 ? (mx * dx + mz * dz) / d : Math.sqrt(mx * mx + mz * mz);
        double speed = Math.max(Route.RUN_SPEED, along);
        Route.Waypoint w = route.waypoints.get(stage);
        if (!w.needsJump || d < 1e-9) return h / speed;
        double rest = h - d;
        int landing = Flight.landingTick(w.top - y);
        if (landing < 0) return h / speed + 20;
        // Run n ticks towards the aim, then jump: the first n whose jump carries the centre over the
        // gap. The run must stay on the ground it starts on, apart from the one late-jump tick
        // after walking off the edge.
        double ux = d > 1e-9 ? dx / d : 0, uz = d > 1e-9 ? dz / d : 0;
        double u = Math.max(0, along), run = 0;
        boolean offEdge = false;
        for (int n = 0; n < 30; n++) {
            if (run + Flight.jumpDistance(u, landing - 1) >= d) return n + landing + rest / Route.RUN_SPEED;
            if (offEdge) return n + landing + rest / Route.RUN_SPEED + MISS_TICKS_PER_BLOCK * (d - run - Flight.jumpDistance(u, landing - 1));
            run += u + Flight.GROUND_ACCEL;
            u = (u + Flight.GROUND_ACCEL) * Flight.GROUND_FRICTION;
            offEdge = route.support(x + ux * run, z + uz * run, y - 1e-6, y + 1e-6) == null;
        }
        // By now the run is at full speed; the rest is linear.
        double left = d - run - Flight.jumpDistance(u, landing - 1);
        return 30 + left / (u + Flight.GROUND_ACCEL) + landing + rest / Route.RUN_SPEED;
    }

    /** Falling below both the platform behind and the waypoint ahead: the jump has failed. */
    private boolean doomed(Node c) {
        PlayerState s = c.state;
        if (s.posY < route.floorY - 1.0) return true;
        if (s.onGround || s.motionY >= 0 || s.inWater) return false;
        double ahead = route.waypoints.get(c.stage).top;
        double behind = c.stage == 0 ? route.startTop : route.waypoints.get(c.stage - 1).top;
        return s.posY < Math.min(ahead, behind) - 0.6;
    }

    private static long key(Node n) {
        PlayerState st = n.state;
        long h = (long) Math.floor(st.posX / 0.02);
        h = h * 1000003L + (long) Math.floor(st.posZ / 0.02);
        h = h * 1000003L + (long) Math.floor(st.posY / 0.05);
        h = h * 1000003L + (long) Math.floor(st.motionX / 0.01);
        h = h * 1000003L + (long) Math.floor(st.motionZ / 0.01);
        h = h * 1000003L + (long) Math.floor(st.motionY / 0.02);
        h = h * 31L + (st.onGround ? 1 : 0);
        h = h * 31L + (st.sprinting ? 1 : 0);
        h = h * 31L + (n.entry >= 0 ? 1 : 0);
        h = h * 31L + n.stage;
        return h;
    }
}
