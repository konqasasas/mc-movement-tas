package io.github.konqasasas.mctas.search.exact;

import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.Region;
import io.github.konqasasas.mctas.problem.input.TickInput;
import io.github.konqasasas.mctas.problem.judge.Evaluator;
import io.github.konqasasas.mctas.problem.judge.RunResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds fast input sequences on the exact physics with a time-synchronous beam search. It gives
 * upper bounds (a run that is known to work), not proofs; {@code LowerBound} gives the other side.
 *
 * <p>Inputs per tick: sprinting forward, or forward with a strafe key (45 degrees off the view), with
 * or without a jump, toward a spread of headings around the next waypoint, plus releasing the keys
 * and walking back. The route of waypoints (the blocks to land on) comes from {@link Route}.
 * Sprint and sneak state changes are kept at least {@link #TOGGLE_GAP} ticks apart, as replay on a
 * server needs (see the README).
 */
public final class Beam {

    /** Minimum ticks between two ticks that change the sprint or sneak state. */
    public static final int TOGGLE_GAP = 3;

    public static final class Result {
        public final List<TickInput> inputs;
        public final RunResult run;

        Result(List<TickInput> inputs, RunResult run) {
            this.inputs = inputs;
            this.run = run;
        }
    }

    private static final class Node {
        final Node parent;
        final PlayerInput input;
        final PlayerState s;
        final int entry;
        final boolean inStart;
        final int stage;
        final int lastToggle;
        double score;

        Node(Node parent, PlayerInput input, PlayerState s, int entry, boolean inStart, int stage, int lastToggle) {
            this.parent = parent;
            this.input = input;
            this.s = s;
            this.entry = entry;
            this.inStart = inStart;
            this.stage = stage;
            this.lastToggle = lastToggle;
        }
    }

    private static final double[] OFFSETS = {0, 2, -2, 5, -5, 10, -10, 20, -20, 35, -35, 55, -55, 90, -90};

    private final Problem problem;
    private final ExactModel model;
    private final List<Region> waypoints;
    private final int width;
    private final double floorY;
    public int extraTicks = 30;

    public Beam(Problem problem, int width) {
        this.problem = problem;
        this.model = new ExactModel(problem);
        this.waypoints = new ArrayList<Region>(Route.landings(problem));
        this.waypoints.add(problem.goal);
        this.width = width;
        double lowest = Math.min(problem.goal.minY, problem.spawn.pos.y);
        for (Region r : waypoints) lowest = Math.min(lowest, r.minY);
        this.floorY = lowest - 0.01;
    }

    public List<Region> waypoints() {
        return waypoints;
    }

    public Result run(int horizon) {
        Region st = problem.start;
        boolean spawnIn = st.contains(problem.spawn.pos);
        Node root = new Node(null, null, model.spawn(problem), spawnIn ? 0 : -1, spawnIn, 0, -100);
        List<Node> beam = Collections.singletonList(root);
        Node best = null;
        int bestTicks = Integer.MAX_VALUE;
        int stopAt = horizon;
        int last = waypoints.size() - 1;
        for (int t = 1; t <= stopAt && !beam.isEmpty(); t++) {
            Map<Long, Node> buckets = new HashMap<Long, Node>();
            for (Node n : beam) {
                for (PlayerInput a : actions(n)) {
                    PlayerState ns = model.tick(n.s, a);
                    boolean toggled = ns.sprinting != n.s.sprinting || ns.prevSneak != n.s.prevSneak;
                    if (toggled && t - n.lastToggle < TOGGLE_GAP) continue;
                    int toggle = toggled ? t : n.lastToggle;
                    if (ns.posY < problem.voidY || ns.posY < floorY) continue;
                    if (n.stage == last && n.entry >= 0 && problem.goal.contains(ns.posX, ns.posY, ns.posZ)) {
                        int ticks = t - n.entry;
                        if (ticks < bestTicks) {
                            bestTicks = ticks;
                            best = new Node(n, a, ns, n.entry, false, n.stage, toggle);
                            stopAt = Math.min(horizon, t + extraTicks);
                        }
                        continue;
                    }
                    boolean in = st.contains(ns.posX, ns.posY, ns.posZ);
                    int entry = in && !n.inStart ? t : n.entry;
                    int stage = n.stage;
                    if (stage < last && ns.onGround && waypoints.get(stage).contains(ns.posX, ns.posY, ns.posZ)) stage++;
                    Node c = new Node(n, a, ns, entry, in, stage, toggle);
                    if (c.entry >= 0 && best != null && t - c.entry >= bestTicks) continue;
                    c.score = score(c, t);
                    long key = bucket(c);
                    Node old = buckets.get(key);
                    if (old == null || c.score > old.score) buckets.put(key, c);
                }
            }
            List<Node> started = new ArrayList<Node>();
            List<Node> waiting = new ArrayList<Node>();
            for (Node n : buckets.values()) (n.entry >= 0 ? started : waiting).add(n);
            beam = new ArrayList<Node>();
            beam.addAll(select(started, width));
            beam.addAll(select(waiting, width / 2));
        }
        if (best == null) return null;
        List<TickInput> inputs = new ArrayList<TickInput>();
        for (Node n = best; n.parent != null; n = n.parent) inputs.add(ExactModel.fromPhysics(n.input));
        Collections.reverse(inputs);
        return new Result(inputs, Evaluator.run(problem, model, inputs));
    }

    private List<PlayerInput> actions(Node n) {
        PlayerState s = n.s;
        Region g = waypoints.get(n.stage);
        double gx = (g.minX + g.maxX) / 2, gz = (g.minZ + g.maxZ) / 2;
        float heading = (float) Math.toDegrees(Math.atan2(-(gx - s.posX), gz - s.posZ));
        List<PlayerInput> out = new ArrayList<PlayerInput>(100);
        int sprintFwd = PlayerInput.W | PlayerInput.SPRINT;
        for (int j = 0; j < (s.onGround ? 2 : 1); j++) {
            int jump = j == 1 ? PlayerInput.JUMP : 0;
            for (double off : OFFSETS) {
                float dir = (float) (heading + off);
                out.add(PlayerInput.of(sprintFwd | jump, dir));
                // W+A moves 45 degrees to the right of the view in yaw terms, W+D to the left.
                out.add(PlayerInput.of(sprintFwd | PlayerInput.A | jump, dir + 45.0F));
                out.add(PlayerInput.of(sprintFwd | PlayerInput.D | jump, dir - 45.0F));
            }
        }
        out.add(PlayerInput.of(0, heading));
        out.add(PlayerInput.of(PlayerInput.S, heading));
        return out;
    }

    private List<Node> select(List<Node> nodes, int cap) {
        Collections.sort(nodes, new Comparator<Node>() {
            @Override
            public int compare(Node a, Node b) {
                return Double.compare(b.score, a.score);
            }
        });
        int perPhase = Math.max(1, cap / 16);
        Map<Long, Integer> counts = new HashMap<Long, Integer>();
        List<Node> out = new ArrayList<Node>();
        List<Node> rest = new ArrayList<Node>();
        for (Node n : nodes) {
            if (out.size() >= cap) break;
            long phase = ((long) Math.floor(n.s.posY * 100.0) * 4 + (n.s.onGround ? 1 : 0) + (n.s.motionY > 0 ? 2 : 0)) * 64 + n.stage;
            Integer c = counts.get(phase);
            int k = c == null ? 0 : c;
            if (k < perPhase) {
                counts.put(phase, k + 1);
                out.add(n);
            } else {
                rest.add(n);
            }
        }
        for (Node n : rest) {
            if (out.size() >= cap) break;
            out.add(n);
        }
        return out;
    }

    private double score(Node n, int t) {
        PlayerState s = n.s;
        Region g = waypoints.get(n.stage);
        double gx = (g.minX + g.maxX) / 2, gz = (g.minZ + g.maxZ) / 2;
        double dx = gx - s.posX, dz = gz - s.posZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        double along = dist > 1e-9 ? (s.motionX * dx + s.motionZ * dz) / dist : 0;
        for (int i = n.stage + 1; i < waypoints.size(); i++) {
            Region a = waypoints.get(i - 1), b = waypoints.get(i);
            dist += Math.hypot((a.minX + a.maxX - b.minX - b.maxX) / 2, (a.minZ + a.maxZ - b.minZ - b.maxZ) / 2);
        }
        if (n.entry < 0) return 10.0 * along - 0.1 * dist;
        return -dist / 0.3 + 4.0 * along - (t - n.entry);
    }

    private static long bucket(Node n) {
        PlayerState s = n.s;
        long h = (long) Math.floor(s.posX / 0.03);
        h = h * 1000003L + (long) Math.floor(s.posZ / 0.03);
        h = h * 1000003L + (long) Math.floor(s.posY / 0.05);
        h = h * 1000003L + (long) Math.floor(s.motionX / 0.01);
        h = h * 1000003L + (long) Math.floor(s.motionZ / 0.01);
        h = h * 31L + (s.onGround ? 1 : 0);
        h = h * 31L + (s.sprinting ? 1 : 0);
        h = h * 31L + (n.entry >= 0 ? 1 : 0);
        h = h * 31L + n.stage;
        return h;
    }
}
