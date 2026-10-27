package io.github.konqasasas.mctas.problem.baseline;

import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.Region;
import io.github.konqasasas.mctas.problem.input.Key;
import io.github.konqasasas.mctas.problem.input.TickInput;
import io.github.konqasasas.mctas.problem.judge.Judge;
import io.github.konqasasas.mctas.problem.judge.PhysicsModel;
import io.github.konqasasas.mctas.problem.judge.RunResult;
import io.github.konqasasas.mctas.physics.PlayerState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class BeamSearch {
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
        final TickInput input;
        final PlayerState state;
        final int startEntry;
        final boolean inStart;
        final int stage;
        double score;

        Node(Node parent, TickInput input, PlayerState state, int startEntry, boolean inStart, int stage) {
            this.parent = parent;
            this.input = input;
            this.state = state;
            this.startEntry = startEntry;
            this.inStart = inStart;
            this.stage = stage;
        }
    }

    private static final Comparator<Node> BY_SCORE = new Comparator<Node>() {
        @Override
        public int compare(Node a, Node b) {
            return Double.compare(b.score, a.score);
        }
    };

    private final Problem problem;
    private final PhysicsModel model;
    private final List<Region> waypoints;
    private final List<TickInput> actions = new ArrayList<TickInput>();
    private final List<TickInput> brakes = new ArrayList<TickInput>();
    private final int beamWidth;
    private final double floorY;

    public BeamSearch(Problem problem, float[] yaws, List<Region> waypoints, int beamWidth) {
        this.problem = problem;
        this.model = new PhysicsModel(problem);
        this.waypoints = new ArrayList<Region>(waypoints);
        this.waypoints.add(problem.goal);
        this.beamWidth = beamWidth;
        double lowest = Math.min(problem.goal.minY, problem.spawn.pos.y);
        for (Region r : waypoints) lowest = Math.min(lowest, r.minY);
        this.floorY = lowest - 0.01;
        Key[][] combos = {{Key.W, Key.SPRINT}, {Key.W, Key.A, Key.SPRINT}, {Key.W, Key.D, Key.SPRINT}, {}, {Key.S}};
        for (float yaw : yaws) {
            for (Key[] c : combos) {
                EnumSet<Key> k = EnumSet.noneOf(Key.class);
                Collections.addAll(k, c);
                actions.add(new TickInput(k, yaw, 0.0F));
                EnumSet<Key> j = EnumSet.copyOf(k);
                j.add(Key.JUMP);
                actions.add(new TickInput(j, yaw, 0.0F));
            }
            brakes.add(new TickInput(EnumSet.noneOf(Key.class), yaw, 0.0F));
            brakes.add(new TickInput(EnumSet.of(Key.S), yaw, 0.0F));
            brakes.add(new TickInput(EnumSet.of(Key.W, Key.SPRINT), yaw, 0.0F));
        }
    }

    public Result run(int horizon, int extraTicksAfterFirstFinish) {
        Region s = problem.start;
        boolean spawnInStart = s.contains(problem.spawn.pos);
        Node root = new Node(null, null, model.spawn(problem), spawnInStart ? 0 : -1, spawnInStart, 0);
        List<Node> beam = Collections.singletonList(root);
        Node best = null;
        int bestTicks = Integer.MAX_VALUE;
        int stopAt = horizon;
        int last = waypoints.size() - 1;
        for (int t = 1; t <= stopAt && !beam.isEmpty(); t++) {
            Map<Long, Node> buckets = new HashMap<Long, Node>();
            for (Node n : beam) {
                for (TickInput a : actions) {
                    PlayerState ns = model.tick(n.state, a);
                    double x = ns.posX, y = ns.posY, z = ns.posZ;
                    if (y < problem.voidY || y < floorY) continue;
                    if (n.stage == last && n.startEntry >= 0 && problem.goal.contains(x, y, z)) {
                        int ticks = t - n.startEntry;
                        if (ticks < bestTicks) {
                            bestTicks = ticks;
                            best = new Node(n, a, ns, n.startEntry, false, n.stage);
                            stopAt = Math.min(horizon, t + extraTicksAfterFirstFinish);
                        }
                        continue;
                    }
                    if (doomed(ns)) continue;
                    boolean in = s.contains(x, y, z);
                    int entry = in && !n.inStart ? t : n.startEntry;
                    int stage = n.stage;
                    if (stage < last && ns.onGround && waypoints.get(stage).contains(x, y, z)) stage++;
                    Node child = new Node(n, a, ns, entry, in, stage);
                    child.score = score(child, t);
                    long key = bucket(child);
                    Node old = buckets.get(key);
                    if (old == null || child.score > old.score) buckets.put(key, child);
                }
            }
            List<Node> started = new ArrayList<Node>();
            List<Node> waiting = new ArrayList<Node>();
            for (Node n : buckets.values()) (n.startEntry >= 0 ? started : waiting).add(n);
            beam = new ArrayList<Node>();
            beam.addAll(select(started));
            beam.addAll(select(waiting));
        }
        if (best == null) return null;
        List<TickInput> inputs = new ArrayList<TickInput>();
        for (Node n = best; n.parent != null; n = n.parent) inputs.add(n.input);
        Collections.reverse(inputs);
        Judge judge = new Judge(problem);
        PlayerState st = model.spawn(problem);
        for (TickInput in : inputs) {
            st = model.tick(st, in);
            if (judge.observe(model.position(st))) break;
        }
        return new Result(inputs, judge.result());
    }

    private boolean doomed(PlayerState st) {
        for (TickInput brake : brakes) {
            PlayerState s = st;
            for (int i = 0; i < 40; i++) {
                s = model.tick(s, brake);
                if (problem.goal.contains(s.posX, s.posY, s.posZ)) return false;
                if (s.onGround && Math.abs(s.motionX) < 0.01 && Math.abs(s.motionZ) < 0.01) return false;
                if (s.posY < floorY) break;
            }
        }
        return true;
    }

    private List<Node> select(List<Node> nodes) {
        Collections.sort(nodes, BY_SCORE);
        int cap = Math.max(1, beamWidth / 16);
        Map<Long, Integer> perPhase = new HashMap<Long, Integer>();
        List<Node> out = new ArrayList<Node>();
        List<Node> rest = new ArrayList<Node>();
        for (Node n : nodes) {
            if (out.size() >= beamWidth) break;
            long phase = ((long) Math.floor(n.state.posY * 100.0) * 4 + (n.state.onGround ? 1 : 0)
                + (n.state.motionY > 0 ? 2 : 0)) * 64 + n.stage;
            Integer c = perPhase.get(phase);
            int count = c == null ? 0 : c;
            if (count < cap) {
                perPhase.put(phase, count + 1);
                out.add(n);
            } else {
                rest.add(n);
            }
        }
        for (Node n : rest) {
            if (out.size() >= beamWidth) break;
            out.add(n);
        }
        return out;
    }

    private double score(Node n, int t) {
        PlayerState st = n.state;
        Region g = waypoints.get(n.stage);
        double gx = (g.minX + g.maxX) / 2.0, gz = (g.minZ + g.maxZ) / 2.0;
        double dx = gx - st.posX, dz = gz - st.posZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        double along = dist > 1e-9 ? (st.motionX * dx + st.motionZ * dz) / dist : 0.0;
        double stageBonus = 1000.0 * n.stage;
        if (n.startEntry < 0) return stageBonus + 10.0 * along - 0.1 * dist;
        return stageBonus - dist / 0.3 + 4.0 * along - (t - n.startEntry);
    }

    private static long bucket(Node n) {
        PlayerState st = n.state;
        long h = (long) Math.floor(st.posX / 0.05);
        h = h * 1000003L + (long) Math.floor(st.posZ / 0.05);
        h = h * 1000003L + (long) Math.floor(st.posY / 0.25);
        h = h * 1000003L + (long) Math.floor(st.motionX / 0.02);
        h = h * 1000003L + (long) Math.floor(st.motionZ / 0.02);
        h = h * 31L + (st.onGround ? 1 : 0);
        h = h * 31L + (n.startEntry >= 0 ? 1 : 0);
        h = h * 31L + n.stage;
        return h;
    }
}
