package io.github.konqasasas.mctas.approx;

import io.github.konqasasas.mctas.physics.Physics;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.problem.Problem;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Local improvement of a finished input list: keep a prefix of it and search the rest again with a
 * wider beam. The rest is a smaller problem, so the same time buys a much wider beam there. Cuts go
 * a few ticks before the start entry, then before each landing (where the next jump is set up),
 * latest first, and the search
 * starts over from the latest cut whenever the solution improves.
 */
final class Refiner {
    static final class Outcome {
        final List<PlayerInput> inputs;
        final int ticks;

        Outcome(List<PlayerInput> inputs, int ticks) {
            this.inputs = inputs;
            this.ticks = ticks;
        }
    }

    /** Ticks before a landing to cut at. */
    private static final int[] LEAD = {0, 4, 8};
    /** Ticks before the start entry to cut at. */
    private static final int[] RUN_UP_BACK = {3, 8, 15};
    static final int WIDTH = 1000;

    private final Problem problem;
    private final Route route;
    private long deadline = Long.MAX_VALUE;

    Refiner(Problem problem, Route route) {
        this.problem = problem;
        this.route = route;
    }

    Refiner deadline(long epochMillis) {
        this.deadline = epochMillis;
        return this;
    }

    Outcome improve(List<PlayerInput> inputs, int ticks) {
        Outcome h = jumpSooner(problem, inputs, ticks, deadline);
        inputs = h.inputs;
        ticks = h.ticks;
        boolean better = true;
        while (better && System.currentTimeMillis() < deadline) {
            better = false;
            List<Integer> cuts = cuts(inputs);
            for (int k = 0; k < cuts.size() && System.currentTimeMillis() < deadline; k++) {
                int cut = cuts.get(k);
                long now = System.currentTimeMillis();
                // Leave the later cuts and the rest of the budget a fair share.
                long until = now + (deadline - now) / Math.max(2, cuts.size() - k);
                TickBeam.Result r = new TickBeam(problem, route, WIDTH).deadline(until).runFrom(inputs.subList(0, cut), ticks);
                if (r != null && r.ticks < ticks && Rollout.ticks(problem, r.inputs, new int[1]) == r.ticks) {
                    inputs = r.inputs;
                    ticks = r.ticks;
                    better = true;
                    break;
                }
            }
        }
        return new Outcome(inputs, ticks);
    }

    /** Ground ticks after a landing that {@link #jumpSooner} tries to cut. */
    static final int MAX_GROUND = 6;

    /**
     * Jumping again sooner after landing: where the run stays on the ground for 2 to
     * {@link #MAX_GROUND} ticks between a landing and the next take-off, drop some of those ground
     * ticks (the rest of the inputs move earlier) and keep the change when the run still finishes,
     * sooner. The take-off's own input is kept, so it still jumps.
     */
    static Outcome jumpSooner(Problem problem, List<PlayerInput> inputs, int ticks, long deadline) {
        boolean better = true;
        while (better && System.currentTimeMillis() < deadline) {
            better = false;
            List<PlayerState> st = new ArrayList<PlayerState>();
            PlayerState s = Rollout.spawn(problem);
            st.add(s);
            for (PlayerInput a : inputs) {
                s = Physics.tick(problem.world, s, a);
                st.add(s);
            }
            // Latest landings first: an edit there leaves the earlier ones as they were.
            for (int t = st.size() - 2; t >= 1 && !better && System.currentTimeMillis() < deadline; t--) {
                if (!st.get(t).onGround || st.get(t - 1).onGround) continue;
                int j = t;
                while (j < st.size() && st.get(j).onGround) j++;
                int ground = j - t;
                if (j >= st.size() || ground < 2 || ground > MAX_GROUND) continue;
                for (int del = ground - 1; del >= 1; del--) {
                    List<PlayerInput> in = new ArrayList<PlayerInput>(inputs.subList(0, t));
                    in.addAll(inputs.subList(t + del, inputs.size()));
                    int n = Rollout.ticks(problem, in, new int[1]);
                    if (n > 0 && n < ticks) {
                        inputs = in;
                        ticks = n;
                        better = true;
                        break;
                    }
                }
            }
        }
        return new Outcome(inputs, ticks);
    }

    /** Cut points: a few before the start entry, then the ones after it, latest first. */
    private List<Integer> cuts(List<PlayerInput> inputs) {
        PlayerState s = Rollout.spawn(problem);
        boolean in = problem.start.contains(s.posX, s.posY, s.posZ);
        int entry = in ? 0 : -1;
        int stage = 0;
        List<Integer> landings = new ArrayList<Integer>();
        for (int t = 1; t <= inputs.size(); t++) {
            s = Physics.tick(problem.world, s, inputs.get(t - 1));
            boolean now = problem.start.contains(s.posX, s.posY, s.posZ);
            if (now && !in) entry = t;
            in = now;
            int next = route.after(stage, s.posX, s.posY, s.posZ, s.onGround);
            if (next > stage) {
                stage = next;
                landings.add(t);
            }
        }
        // The last landing before the goal, and the goal itself, count as landings too.
        landings.add(inputs.size());
        // First, before the entry: a run-up that enters the start box on a better tick (on the jump, say).
        List<Integer> out = new ArrayList<Integer>();
        if (entry >= 0) for (int back : RUN_UP_BACK) if (entry - back >= 0) out.add(entry - back);
        TreeSet<Integer> cuts = new TreeSet<Integer>();
        if (entry >= 0) cuts.add(entry);
        for (int l : landings) {
            for (int lead : LEAD) {
                int c = l - lead;
                if (entry >= 0 && c >= entry && c < inputs.size()) cuts.add(c);
            }
        }
        out.addAll(cuts.descendingSet());
        return out;
    }
}
