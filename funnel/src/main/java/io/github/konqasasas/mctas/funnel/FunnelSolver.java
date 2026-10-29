package io.github.konqasasas.mctas.funnel;

import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.input.Key;
import io.github.konqasasas.mctas.problem.input.TickInput;
import io.github.konqasasas.mctas.problem.judge.Evaluator;
import io.github.konqasasas.mctas.problem.judge.RunResult;
import io.github.konqasasas.mctas.problem.judge.ToggleRule;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The backward funnel search end to end.
 *
 * <p>Backward: anchors (standing still on a block top) are verified from the goal outwards. Each step
 * takes the unverified anchors with the best guess of a run through them (the backward field's
 * time to the verified set plus the forward field's time from the start) and runs a {@link Beam} from
 * standing still on each; a beam that reaches the goal or lands on a verified anchor verifies its
 * anchor with that time, which seeds the backward field. Anchors that a beam cannot get out of stay
 * unverified, and beams pass over them (a jump that needs the speed of the jump before it is found
 * from the anchor before).
 *
 * <p>Forward: from the spawn, a best-first search over landings, each expansion a beam that must land
 * on a verified anchor with a lower time than the one it starts on (or reach the goal), ranked by
 * clock plus the anchor's time. It runs whenever the verified set has grown near the start.
 */
public final class FunnelSolver {
    public static final String ENGINE = "funnel-v1";

    public static final class Solution {
        public final List<TickInput> inputs;
        public final int ticks;
        public final long foundMillis;

        Solution(List<TickInput> inputs, int ticks, long foundMillis) {
            this.inputs = inputs;
            this.ticks = ticks;
            this.foundMillis = foundMillis;
        }
    }

    /** Forward expansions per anchor, and how much the anchor's time weighs in the order of the forward search. */
    static final int PER_ANCHOR = 3;
    /** Entries into the start box the forward run starts from, when the spawn is outside it. */
    static final int ENTRIES = 6;
    static final float DEPTH = 0.15F;

    static final int MAX_ATTEMPTS = 4;
    /** Provisional times past a stuck stretch ({@link #bridge}): how far apart, how many, and how they are guessed. */
    static final double BRIDGE_RADIUS = 2.5;
    static final int MAX_BRIDGES = 40;
    static final float BRIDGE_FACTOR = 1.5F, BRIDGE_ADD = 10;
    private int bridges;
    /** Anchors are tried once the field puts them at most this many ticks of moves from a verified one. */
    static final float GAP = 30;
    /** A verified anchor lets the failed anchors this close (blocks) try again. */
    static final double RETRY_RADIUS = 8;
    static final boolean DEBUG = System.getenv("FUNNEL_DEBUG") != null;

    private final Problem problem;
    private final long budgetMillis;
    private PrintStream log;
    int threads = Math.max(1, Runtime.getRuntime().availableProcessors());

    public FunnelSolver(Problem problem, long budgetMillis) {
        this.problem = problem;
        this.budgetMillis = budgetMillis;
    }

    public FunnelSolver log(PrintStream out) {
        this.log = out;
        return this;
    }

    public FunnelSolver threads(int n) {
        this.threads = Math.max(1, n);
        return this;
    }

    private void say(String s) {
        if (log != null) log.println("  " + s);
    }

    private Grid grid;
    private Anchors anchors;
    private Field back, fwd;
    /** Moves from each cell to the nearest verified anchor or the goal (all seeded at 0). */
    private Field near;
    private Landing landing;
    private long t0;

    public Solution solve() {
        t0 = System.currentTimeMillis();
        long deadline = t0 + budgetMillis;
        grid = new Grid(problem);
        anchors = new Anchors(grid);
        fwd = new Field(grid, true);
        fwd.seed(grid.cellsIn(problem.start), 0);
        // Backward fields keep to what the start reaches: a shortcut through cells no run gets to is no guide.
        back = new Field(grid, false, fwd);
        back.seed(grid.cellsIn(problem.goal), 0);
        near = new Field(grid, false, fwd);
        near.seed(grid.cellsIn(problem.goal), 0);
        landing = new Landing(grid, back);
        Beam.Node spawn = spawn();
        say(String.format("grid %dx%dx%d, %d anchors, field at spawn %.1f, goal from start %.1f (%d ms)", grid.nx, grid.nz, grid.ny,
            anchors.list.size(), back.at(spawn.s.posX, spawn.s.posY, spawn.s.posZ), minFwd(grid.cellsIn(problem.goal)),
            System.currentTimeMillis() - t0));
        if (System.getenv("FUNNEL_PROBE") != null) probe(System.getenv("FUNNEL_PROBE"));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Assembled best = null;
        try {
            int verified = 0, tried = 0, lastAssembly = -1;
            float nearest = Field.INF;
            // When the verified set last came nearer the start, and how long it may stand still.
            long progress = System.currentTimeMillis();
            float progressAt = Field.INF;
            long stall = Math.max(8000, budgetMillis / 15);
            while (System.currentTimeMillis() < deadline) {
                if (nearest < progressAt - 2) {
                    progressAt = nearest;
                    progress = System.currentTimeMillis();
                }
                List<Anchors.Anchor> batch = System.currentTimeMillis() - progress > stall ? new ArrayList<Anchors.Anchor>() : pick(threads, false, GAP);
                boolean stuck = batch.isEmpty() && System.currentTimeMillis() - progress > stall;
                // Stalled: the failed anchors try again with a wider, longer beam, and anchors further
                // from the verified ones get tried (past a long ladder, which has no anchors).
                for (float gap = 2 * GAP; batch.isEmpty() && !stuck && gap <= 8 * GAP; gap *= 2) batch = pick(threads, true, gap);
                if (batch.isEmpty()) {
                    progress = System.currentTimeMillis();
                    // Stuck at a stretch no beam gets across: guess times for its start side and go on
                    // from there; the forward run has to cross it for real.
                    List<Anchors.Anchor> bridged = bridge();
                    if (bridged.isEmpty()) break;
                    for (Anchors.Anchor a : bridged) nearest = Math.min(nearest, minFwd(a.cells));
                    landing.refresh();
                    continue;
                }
                List<Future<Beam.Hit>> fs = new ArrayList<Future<Beam.Hit>>();
                for (final Anchors.Anchor a : batch) {
                    a.attempts++;
                    a.retry = false;
                    fs.add(pool.submit(new Callable<Beam.Hit>() {
                        @Override
                        public Beam.Hit call() {
                            return verify(a, deadline);
                        }
                    }));
                }
                for (int i = 0; i < batch.size(); i++) {
                    Beam.Hit h;
                    try {
                        h = fs.get(i).get();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    tried++;
                    Anchors.Anchor a = batch.get(i);
                    if (DEBUG) say(String.format("  anchor %s top %.2f try %d guess %.1f + %.1f: %s", a, a.top, a.attempts, guess(a)[0], minFwd(a.cells),
                        h == null ? "no hit" : String.format("%.1f via %s in %d ticks%s", h.value, h.anchor == null ? "goal" : h.anchor.toString(), h.inputs.size(), a.moving ? " at speed" : "")));
                    if (h == null) continue;
                    a.v = h.value;
                    a.witness = h.inputs;
                    a.via = h.anchor == null ? -1 : h.anchor.id;
                    back.seed(a.cells, a.v);
                    near.seed(a.cells, 0);
                    verified++;
                    for (Anchors.Anchor b : anchors.list) {
                        if (b.verified()) continue;
                        double dx = b.cx - a.cx, dy = b.top - a.top, dz = b.cz - a.cz;
                        if (b.attempts == 0 || b.attempts >= MAX_ATTEMPTS) continue;
                        if (dx * dx + dy * dy + dz * dz <= RETRY_RADIUS * RETRY_RADIUS) b.retry = true;
                    }
                    nearest = Math.min(nearest, minFwd(a.cells));
                }
                landing.refresh();
                // Try the forward run once the verified set comes near the start, then as it grows.
                boolean near = nearest < 40;
                if (near && (lastAssembly < 0 || verified >= lastAssembly * 1.25 + 10)) {
                    lastAssembly = verified;
                    say(String.format("%d of %d tried verified, nearest to the start %.1f (%d ms)", verified, tried, nearest, System.currentTimeMillis() - t0));
                    Assembled a = assemble(deadline);
                    if (a != null && (best == null || a.ticks < best.ticks)) {
                        best = a;
                        say("forward run: " + a.ticks + " ticks (" + best.foundMillis + " ms)");
                    }
                }
            }
            if (DEBUG) {
                List<Anchors.Anchor> by = new ArrayList<Anchors.Anchor>(anchors.list);
                by.sort((p, q) -> Float.compare(minFwd(p.cells), minFwd(q.cells)));
                for (Anchors.Anchor a : by) {
                    float gp = Field.INF;
                    for (int c : a.cells) gp = Math.min(gp, near.value(c));
                    say(String.format("  %s top %.2f from start %.1f back %.1f gap %.1f: %s", a, a.top, minFwd(a.cells), guess(a)[0], gp,
                        a.verified() ? String.format("%.1f via %s%s", a.v, a.via < 0 ? "goal" : anchors.list.get(a.via).toString(), a.moving ? " at speed" : "")
                            : a.attempts == 0 ? "not tried" : "failed " + a.attempts));
                }
            }
            say(String.format("backward search: %d of %d tried verified, %d anchors (%d ms)", verified, tried, anchors.list.size(), System.currentTimeMillis() - t0));
            if (best == null && verified > lastAssembly) {
                Assembled a = assemble(Math.max(deadline, System.currentTimeMillis() + 5000));
                if (a != null) best = a;
            }
        } finally {
            pool.shutdownNow();
        }
        if (best == null) return null;
        return new Solution(best.inputs, best.ticks, best.foundMillis);
    }

    /** Debugging: the cells of x along z0..z1 by level: flags and backward field value. */
    private void probe(String spec) {
        String[] p = spec.split(",");
        double x = Double.parseDouble(p[0]);
        double z0 = Double.parseDouble(p[1]), z1 = Double.parseDouble(p[2]);
        for (int l = grid.ny - 1; l >= 0; l--) {
            StringBuilder sb = new StringBuilder(String.format("%3d ", grid.y0 + l));
            for (double z = z0; z <= z1 + 1e-9; z += 0.5) {
                int id = grid.cellOf(x, grid.y0 + l + 0.5, z);
                if (id < 0) {
                    sb.append("   ?");
                    continue;
                }
                byte f = grid.flags[id];
                char c = (f & Grid.PASS) == 0 ? '#' : (f & Grid.STAND) != 0 ? 's' : (f & Grid.CLIMB) != 0 ? 'L' : (f & Grid.WATER) != 0 ? 'w' : (f & Grid.AIR) != 0 ? '.' : ' ';
                float v = (System.getenv("FUNNEL_PROBE_FWD") != null ? fwd : back).value(id);
                sb.append(v == Field.INF ? "  " + c + " " : String.format("%3.0f%c", Math.min(999, v), c));
            }
            say(sb.toString());
        }
    }

    private float minFwd(List<Integer> cells) {
        float m = Field.INF;
        for (int c : cells) m = Math.min(m, fwd.value(c));
        return m;
    }

    private float minBack(List<Integer> cells) {
        float m = Field.INF;
        for (int c : cells) m = Math.min(m, back.value(c));
        return m;
    }

    /** {backward field, forward field} at the anchor. */
    private float[] guess(Anchors.Anchor a) {
        return new float[] {back.at(a.cx, a.top, a.cz), minFwd(a.cells)};
    }

    /**
     * Gives the stuck anchors nearest the verified set (the failed ones first) a provisional time: the
     * field's guess times {@link #BRIDGE_FACTOR} plus {@link #BRIDGE_ADD}. They count as verified for
     * the beams of the anchors behind them; the forward run then has to find the way across. Takes the
     * best stuck anchor and those on the same block level within {@link #BRIDGE_RADIUS} blocks of it.
     */
    private List<Anchors.Anchor> bridge() {
        List<Anchors.Anchor> out = new ArrayList<Anchors.Anchor>();
        if (bridges >= MAX_BRIDGES) return out;
        Anchors.Anchor best = null;
        float bestKey = Field.INF;
        for (Anchors.Anchor a : anchors.list) {
            if (a.verified()) continue;
            float[] g = guess(a);
            if (g[0] == Field.INF || g[1] == Field.INF) continue;
            float gap = Field.INF;
            for (int c : a.cells) gap = Math.min(gap, near.value(c));
            if (gap == Field.INF) continue;
            // Nearest the verified set, and of those the ones on the way from the start.
            float key = gap + 0.1F * (g[0] + g[1]) - (a.attempts > 0 ? 5 : 0);
            if (key < bestKey) {
                bestKey = key;
                best = a;
            }
        }
        if (best == null) return out;
        bridges++;
        for (Anchors.Anchor a : anchors.list) {
            if (a.verified() || Math.abs(a.top - best.top) > 0.6) continue;
            double dx = a.cx - best.cx, dz = a.cz - best.cz;
            if (dx * dx + dz * dz > BRIDGE_RADIUS * BRIDGE_RADIUS) continue;
            float g = guess(a)[0];
            if (g == Field.INF || minFwd(a.cells) == Field.INF) continue;
            a.v = g * BRIDGE_FACTOR + BRIDGE_ADD;
            a.provisional = true;
            back.seed(a.cells, a.v);
            near.seed(a.cells, 0);
            out.add(a);
        }
        say(String.format("bridge %d at %s: %d anchors guessed (%d ms)", bridges, best, out.size(), System.currentTimeMillis() - t0));
        return out;
    }

    /** The unverified anchors most likely on a run from the start to the verified set. */
    private List<Anchors.Anchor> pick(int n, boolean stalled, float maxGap) {
        List<Anchors.Anchor> cands = new ArrayList<Anchors.Anchor>();
        final List<Float> prio = new ArrayList<Float>();
        for (Anchors.Anchor a : anchors.list) {
            if (a.verified() || a.attempts >= MAX_ATTEMPTS) continue;
            // Retry only once an anchor near it has been verified.
            if (a.attempts > 0 && !a.retry && !stalled) continue;
            float[] g = guess(a);
            if (g[0] == Field.INF || g[1] == Field.INF) continue;
            // Only near the verified set: a beam has to get there.
            float gap = Field.INF;
            for (int c : a.cells) gap = Math.min(gap, near.value(c));
            if (gap > maxGap) continue;
            cands.add(a);
            prio.add(g[0] + g[1]);
        }
        List<Integer> idx = new ArrayList<Integer>();
        for (int i = 0; i < cands.size(); i++) idx.add(i);
        Collections.sort(idx, (p, q) -> Float.compare(prio.get(p), prio.get(q)));
        List<Anchors.Anchor> out = new ArrayList<Anchors.Anchor>();
        for (int i = 0; i < Math.min(n, idx.size()); i++) out.add(cands.get(idx.get(i)));
        return out;
    }

    /** A beam from standing still on the anchor, and failing that from arriving at speed. */
    private Beam.Hit verify(Anchors.Anchor a, long deadline) {
        Beam beam = new Beam(grid, back, landing, anchors);
        beam.trace = (a.bx + "," + a.by + "," + a.bz).equals(System.getenv("FUNNEL_TRACE"));
        float yaw = back.heading(a.cx, a.top, a.cz);
        if (Float.isNaN(yaw)) yaw = 0;
        for (int run = 0; run < 2; run++) {
            List<Beam.Node> roots = new ArrayList<Beam.Node>();
            if (run == 0) {
                roots.add(new Beam.Node(a.settled(yaw), 0, 0, false, -100));
            } else {
                roots.add(new Beam.Node(a.arriving(yaw), 0, 0, false, -100));
                roots.addAll(a.arrivals());
            }
            Beam.Options o = new Beam.Options();
            float gap = Field.INF;
            for (int c : a.cells) gap = Math.min(gap, near.value(c));
            o.width = a.attempts <= 1 ? 150 : 400;
            // The beam has to cover the moves to the verified set, which the field underrates.
            o.maxTicks = (int) Math.max(a.attempts <= 1 ? 60 : 120, Math.min(400, 2 * gap + 20));
            o.collect = true;
            o.self = a;
            o.deadlineNanos = System.nanoTime() + Math.min(20_000L, Math.max(0, deadline - System.currentTimeMillis())) * 1_000_000L;
            List<Beam.Hit> hits = beam.search(roots, o);
            if (!hits.isEmpty()) {
                a.moving = run == 1;
                return hits.get(0);
            }
        }
        return null;
    }

    private Beam.Node spawn() {
        PlayerState s = PlayerState.standing(problem.spawn.pos.x, problem.spawn.pos.y, problem.spawn.pos.z, problem.spawn.yaw);
        s.rotationPitch = problem.spawn.pitch;
        s.hotbar = problem.inventory;
        s.selectedSlot = problem.selectedSlot;
        boolean in = problem.start.contains(s.posX, s.posY, s.posZ);
        return new Beam.Node(s, 0, in ? 0 : -1, in, -100);
    }

    private static final class Chain {
        final Chain parent;
        final List<PlayerInput> seg;
        final Beam.Node end;
        /** The anchor this chain stands on (null at the spawn and in the goal). */
        final Anchors.Anchor anchor;
        final float est;
        final boolean goal;

        Chain(Chain parent, List<PlayerInput> seg, Beam.Node end, Anchors.Anchor anchor, float est, boolean goal) {
            this.parent = parent;
            this.seg = seg;
            this.end = end;
            this.anchor = anchor;
            this.est = est;
            this.goal = goal;
        }

        float key() {
            return est + (anchor == null ? 0 : DEPTH * anchor.v);
        }

        List<PlayerInput> inputs() {
            List<List<PlayerInput>> parts = new ArrayList<List<PlayerInput>>();
            for (Chain c = this; c != null; c = c.parent) parts.add(c.seg);
            List<PlayerInput> out = new ArrayList<PlayerInput>();
            for (int i = parts.size() - 1; i >= 0; i--) out.addAll(parts.get(i));
            return out;
        }
    }

    private static final class Assembled {
        final List<TickInput> inputs;
        final int ticks;
        final long foundMillis;

        Assembled(List<TickInput> inputs, int ticks, long foundMillis) {
            this.inputs = inputs;
            this.ticks = ticks;
            this.foundMillis = foundMillis;
        }
    }

    /** The forward best-first search over landings from the spawn; null when it finds no way to the goal. */
    private Assembled assemble(long deadline) {
        // By the estimate, a little in favour of the chains nearer the goal (the anchors' times from rest
        // overrate what is left after an arrival at speed, so the estimates of a whole corridor tie).
        PriorityQueue<Chain> open = new PriorityQueue<Chain>((p, q) -> Float.compare(p.key(), q.key()));
        // Expansions per anchor: the first few landings on it (by the order above) stand for the rest.
        Map<Integer, Integer> closed = new HashMap<Integer, Integer>();
        Field toStart = new Field(grid, false);
        toStart.seed(grid.cellsIn(problem.start), 0);
        float startValue = Field.INF;
        for (int id : grid.cellsIn(problem.start)) startValue = Math.min(startValue, back.value(id));
        Beam beam = new Beam(grid, back, landing, anchors).toStart(toStart, startValue);
        int expansions = 0;
        Chain deepest = null;
        Beam.Node sp = spawn();
        float fromStart = fwd.at(sp.s.posX, sp.s.posY, sp.s.posZ);
        int runUp = fromStart < Field.INF ? (int) Math.ceil(fromStart * 1.2) : 0;
        Chain root = new Chain(null, new ArrayList<PlayerInput>(), sp, null, 0, false);
        if (sp.entry < 0) {
            // The run-up on its own: the entries into the start box, each a root of the run. Inside the
            // run's beam, states that hop over the box in step with each other can leave none that enter.
            Beam.Options o = new Beam.Options();
            o.width = 300;
            o.maxTicks = runUp + 60;
            o.hits = ENTRIES;
            o.entries = true;
            o.judgeStart = true;
            o.deadlineNanos = System.nanoTime() + Math.max(0, deadline - System.currentTimeMillis()) * 1_000_000L;
            List<Beam.Hit> in = beam.search(sp, o);
            if (DEBUG) say(String.format("    run-up: %d entries (%d ms)", in.size(), System.currentTimeMillis() - t0));
            for (Beam.Hit h : in) {
                PlayerState e = h.end.s;
                open.add(new Chain(root, h.inputs, h.end, null, back.at(e.posX, e.posY, e.posZ), false));
            }
        }
        if (open.isEmpty()) open.add(root);
        while (!open.isEmpty() && System.currentTimeMillis() < deadline && expansions < 2000) {
            Chain c = open.poll();
            if (c.anchor != null && (deepest == null || c.anchor.v < deepest.anchor.v)) deepest = c;
            if (c.goal) {
                List<PlayerInput> in = c.inputs();
                List<TickInput> ticks = toTicks(in);
                RunResult r = Evaluator.run(problem, ticks);
                if (!r.finished() || !ToggleRule.violations(problem, ticks).isEmpty()) {
                    say("forward run does not pass the judge: " + r.status + ", toggles " + ToggleRule.violations(problem, ticks));
                    continue;
                }
                return new Assembled(ticks.subList(0, r.goalTick), r.ticks(), System.currentTimeMillis() - t0);
            }
            if (c.anchor != null) {
                int n = closed.getOrDefault(c.anchor.id, 0);
                if (n >= PER_ANCHOR) continue;
                closed.put(c.anchor.id, n + 1);
            }
            expansions++;
            Beam.Options o = new Beam.Options();
            // Off a provisional anchor the beam has the stretch to cross that no backward beam crossed.
            boolean across = c.anchor != null && c.anchor.provisional;
            o.width = across ? 500 : 150;
            // From the spawn, also the run to the start (free, but it takes ticks of the beam).
            o.maxTicks = (across ? 240 : 120) + (c.parent == null ? runUp : 0);
            o.hits = 8;
            o.self = c.anchor;
            o.judgeStart = true;
            o.deadlineNanos = System.nanoTime() + Math.max(0, deadline - System.currentTimeMillis()) * 1_000_000L;
            beam.trace = DEBUG && System.getenv("FUNNEL_TRACE_FWD") != null && expansions <= Integer.parseInt(System.getenv("FUNNEL_TRACE_FWD"));
            List<Beam.Hit> hits = beam.search(c.end, o);
            beam.trace = false;
            if (hits.isEmpty() && !across) {
                // A slow section (a ladder, a slot): once more, wider and longer.
                o.width = 500;
                o.maxTicks = 240;
                hits = beam.search(c.end, o);
            }
            if (DEBUG) say(String.format("    expand %s @%d est %.1f: %d hits (%d ms)", c.anchor, c.end.clock(), c.est, hits.size(), System.currentTimeMillis() - t0));
            for (Beam.Hit h : hits) {
                open.add(new Chain(c, h.inputs, h.end, h.anchor, h.value, h.goal()));
            }
        }
        say("forward run found no way (" + expansions + " expansions)");
        if (DEBUG && deepest != null) {
            StringBuilder sb = new StringBuilder("  deepest:");
            List<Chain> cs = new ArrayList<Chain>();
            for (Chain c = deepest; c != null; c = c.parent) cs.add(0, c);
            for (Chain c : cs) {
                if (c.anchor != null) sb.append(String.format(" %s(%.0f @%d v%.2f)", c.anchor, c.anchor.v, c.end.clock(), Beam.speed(c.end.s)));
            }
            say(sb.toString());
            Anchors.Anchor a = deepest.anchor;
            say(String.format("  deepest stands on %s, verified %s via %s, arrivals %d", a, a.moving ? "at speed" : "at rest",
                a.via < 0 ? "goal" : anchors.list.get(a.via).toString(), a.arrivals().size()));
            PlayerState st = deepest.end.s;
            say(String.format("  at (%.3f, %.3f, %.3f) v (%.3f, %.3f, %.3f) sprint %b sneak %b since toggle %d", st.posX, st.posY, st.posZ,
                st.motionX, st.motionY, st.motionZ, st.sprinting, st.isSneaking(), deepest.end.tick - deepest.end.lastToggle));
            Beam b = new Beam(grid, back, landing, anchors);
            b.trace = true;
            Beam.Options o = new Beam.Options();
            o.width = 300;
            o.maxTicks = 120;
            o.self = a;
            Beam.Node rebased = new Beam.Node(st, 0, 0, false, deepest.end.lastToggle - deepest.end.tick);
            say("  from the landing: " + b.search(rebased, o).size() + " hits");
            Beam.Node rest = new Beam.Node(a.settled(st.rotationYaw), 0, 0, false, -100);
            say("  from rest: " + b.search(rest, o).size() + " hits");
        }
        return null;
    }

    static TickInput toTick(PlayerInput a) {
        Set<Key> k = EnumSet.noneOf(Key.class);
        if (a.has(PlayerInput.W)) k.add(Key.W);
        if (a.has(PlayerInput.A)) k.add(Key.A);
        if (a.has(PlayerInput.S)) k.add(Key.S);
        if (a.has(PlayerInput.D)) k.add(Key.D);
        if (a.has(PlayerInput.JUMP)) k.add(Key.JUMP);
        if (a.has(PlayerInput.SPRINT)) k.add(Key.SPRINT);
        if (a.has(PlayerInput.SNEAK)) k.add(Key.SNEAK);
        return new TickInput(k, a.yaw, a.pitch);
    }

    static List<TickInput> toTicks(List<PlayerInput> in) {
        List<TickInput> out = new ArrayList<TickInput>(in.size());
        for (PlayerInput a : in) out.add(toTick(a));
        return out;
    }
}
