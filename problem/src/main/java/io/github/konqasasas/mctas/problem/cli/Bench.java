package io.github.konqasasas.mctas.problem.cli;

import io.github.konqasasas.mctas.physics.Physics;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.physics.testing.RandomScenario;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.io.ProblemIO;
import io.github.konqasasas.mctas.problem.judge.PhysicsModel;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Single-thread speed and memory of the engine, with a checksum of every state it computed so two builds
 * can be compared bit for bit.
 *
 * <ul>
 *   <li>{@code expand}: a search-like tree on each problem. Every tick, each kept state is advanced by
 *       every input of a fixed set (sprinting and walking, strafing, jumping, sneaking, over a spread of
 *       yaws), and a deterministic subset of the distinct children is kept.</li>
 *   <li>{@code scenarios}: the random courses and inputs of the differential tests, of every kind.</li>
 *   <li>{@code memory}: heap bytes per state, for a million distinct states from the expand tree.</li>
 * </ul>
 *
 * <p>Run with {@code ./gradlew :problem:bench -Pproblems=a.problem.json,b.problem.json}. Equal checksums
 * from two builds mean, up to a 64-bit hash collision, that every state they computed is the same bit for bit.
 */
public final class Bench {

    private static final int KEEP = 256;
    private static final int DEPTH = 80;
    private static final float[] YAW_OFFSETS = {0, 3, -3, 10, -10, 25, -25, 45, -45, 90, -90, 180};
    private static final int[] KEYS = {
            PlayerInput.W | PlayerInput.SPRINT,
            PlayerInput.W | PlayerInput.SPRINT | PlayerInput.JUMP,
            PlayerInput.W | PlayerInput.A | PlayerInput.SPRINT,
            PlayerInput.W | PlayerInput.D | PlayerInput.SPRINT | PlayerInput.JUMP,
            PlayerInput.W,
            PlayerInput.W | PlayerInput.SNEAK,
            PlayerInput.S | PlayerInput.JUMP,
            0,
    };

    private Bench() {
    }

    public static void main(String[] args) throws IOException {
        int rounds = 3;
        List<Problem> problems = new ArrayList<Problem>();
        for (String a : args) {
            if (a.startsWith("--rounds=")) {
                rounds = Integer.parseInt(a.substring("--rounds=".length()));
            } else {
                for (String p : a.split(",")) {
                    if (!p.isEmpty()) {
                        problems.add(ProblemIO.read(Paths.get(p)));
                    }
                }
            }
        }
        // The trees, built once; the timed part below only re-runs their ticks.
        List<List<List<PlayerState>>> trees = new ArrayList<List<List<PlayerState>>>();
        long all = 0;
        for (Problem p : problems) {
            List<List<PlayerState>> levels = new ArrayList<List<PlayerState>>();
            long[] res = expand(p, null, levels);
            trees.add(levels);
            all = all * 31 + res[1];
            System.out.printf("expand %-28s ticks %9d checksum %016x%n", p.id, res[0], res[1]);
        }
        System.out.printf("expand checksum %016x%n", all);
        List<RandomScenario> scenarios = new ArrayList<RandomScenario>();
        all = 0;
        for (String course : RandomScenario.COURSES) {
            long cs = 0;
            for (long seed = 1; seed <= 300; seed++) {
                RandomScenario sc = RandomScenario.generate(course, seed, 400);
                scenarios.add(sc);
                PlayerState s = sc.start;
                for (PlayerInput in : sc.inputs) {
                    s = Physics.tick(sc.world, s, in);
                    cs = cs * 0x100000001b3L ^ s.contentHash();
                }
            }
            all = all * 31 + cs;
            System.out.printf("scenarios %-8s checksum %016x%n", course, cs);
        }
        System.out.printf("scenarios checksum %016x%n", all);

        for (int r = 0; r < rounds; r++) {
            long ticks = 0;
            long sink = 0;
            long t0 = System.nanoTime();
            StringBuilder each = new StringBuilder();
            for (int i = 0; i < problems.size(); i++) {
                Problem p = problems.get(i);
                List<PlayerInput> inputs = inputs(p);
                long t1 = System.nanoTime();
                long n = 0;
                for (List<PlayerState> level : trees.get(i)) {
                    for (PlayerState s : level) {
                        for (PlayerInput in : inputs) {
                            sink += Double.doubleToRawLongBits(Physics.tick(p.world, s, in).posX);
                            n++;
                        }
                    }
                }
                ticks += n;
                each.append(String.format(" %s %.0f", p.id, n / ((System.nanoTime() - t1) / 1e9)));
            }
            double sec = (System.nanoTime() - t0) / 1e9;
            System.out.println("  ticks/s by problem:" + each);
            System.out.printf("round %d expand: %d ticks in %.2f s = %.0f ticks/s (%x)%n",
                    r + 1, ticks, sec, ticks / sec, sink & 0xF);

            ticks = 0;
            t0 = System.nanoTime();
            for (RandomScenario sc : scenarios) {
                PlayerState s = sc.start;
                for (PlayerInput in : sc.inputs) {
                    s = Physics.tick(sc.world, s, in);
                    ticks++;
                }
                sink += Double.doubleToRawLongBits(s.posX);
            }
            sec = (System.nanoTime() - t0) / 1e9;
            System.out.printf("round %d scenarios: %d ticks in %.2f s = %.0f ticks/s (%x)%n",
                    r + 1, ticks, sec, ticks / sec, sink & 0xF);
        }
        if (!problems.isEmpty()) {
            memory(problems.get(problems.size() - 1));
        }
    }

    private static List<PlayerInput> inputs(Problem p) {
        List<PlayerInput> inputs = new ArrayList<PlayerInput>();
        for (float off : YAW_OFFSETS) {
            for (int k : KEYS) {
                inputs.add(new PlayerInput(k, p.spawn.yaw + off, p.spawn.pitch));
            }
        }
        return inputs;
    }

    /**
     * Ticks computed and a checksum of every child state. Adds every distinct child to {@code out} and
     * each tick's parents to {@code levels} when given.
     */
    static long[] expand(Problem p, List<PlayerState> out, List<List<PlayerState>> levels) {
        PlayerState spawn = new PhysicsModel(p).spawn(p);
        List<PlayerInput> inputs = inputs(p);
        double floor = p.spawn.pos.y - 12;
        List<PlayerState> frontier = new ArrayList<PlayerState>();
        frontier.add(spawn);
        long ticks = 0;
        long sum = 0;
        for (int d = 0; d < DEPTH && !frontier.isEmpty(); d++) {
            if (levels != null) {
                levels.add(frontier);
            }
            Map<PlayerState, Boolean> seen = new HashMap<PlayerState, Boolean>();
            List<PlayerState> children = new ArrayList<PlayerState>();
            for (PlayerState s : frontier) {
                for (PlayerInput in : inputs) {
                    PlayerState n = Physics.tick(p.world, s, in);
                    ticks++;
                    long h = n.contentHash();
                    sum = sum * 0x100000001b3L ^ h;
                    if (n.posY >= floor && n.posY >= p.voidY && seen.put(n, Boolean.TRUE) == null) {
                        children.add(n);
                    }
                }
            }
            frontier = pick(children);
            if (out != null) {
                out.addAll(children);
            }
        }
        return new long[] {ticks, sum};
    }

    /** Up to {@link #KEEP} children, chosen by their hashes so the tree is the same on every build. */
    private static List<PlayerState> pick(List<PlayerState> children) {
        if (children.size() <= KEEP) {
            return children;
        }
        PlayerState[] a = children.toArray(new PlayerState[0]);
        final long[] keys = new long[a.length];
        Integer[] idx = new Integer[a.length];
        for (int i = 0; i < a.length; i++) {
            long h = a[i].contentHash() * 0x9E3779B97F4A7C15L;
            keys[i] = h ^ (h >>> 31);
            idx[i] = i;
        }
        Arrays.sort(idx, (x, y) -> Long.compare(keys[x], keys[y]));
        List<PlayerState> out = new ArrayList<PlayerState>(KEEP);
        for (int i = 0; i < KEEP; i++) {
            out.add(a[idx[i]]);
        }
        return out;
    }

    /** Retained heap per state, from a million distinct states (each with its own box, as a search keeps them). */
    private static void memory(Problem p) {
        List<PlayerState> states = new ArrayList<PlayerState>();
        expand(p, states, null);
        int n = 1_000_000;
        PlayerState[] keep = new PlayerState[n];
        long before = used();
        for (int i = 0; i < n; i++) {
            PlayerState s = states.get(i % states.size());
            keep[i] = Physics.tick(p.world, s, new PlayerInput(PlayerInput.W | PlayerInput.SPRINT, (float) i, 0));
        }
        long after = used();
        System.out.printf("memory: %.1f bytes per state (%d states, %d kept in the tree)%n",
                (after - before) / (double) n, n, states.size());
        if (keep[n - 1].posX == 12345.678) {
            System.out.println();
        }
    }

    private static long used() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 5; i++) {
            System.gc();
        }
        return rt.totalMemory() - rt.freeMemory();
    }
}
