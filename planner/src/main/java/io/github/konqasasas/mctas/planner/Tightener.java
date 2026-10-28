package io.github.konqasasas.mctas.planner;

import io.github.konqasasas.mctas.physics.PlayerInput;

import java.util.ArrayList;
import java.util.List;

/**
 * The bottom level: re-joins the plan tick by tick. The plan is replayed from its steps, so changing
 * one step (jump a tick earlier or later, strafe the other way, nudge the aim, or drop the step) and
 * replaying the rest from there gives a new whole run; a change is kept when the run still reaches the
 * goal and is faster. Passes repeat until nothing helps or time runs out. Every root is tried too.
 */
final class Tightener {
    private static final double[] NUDGES = {0.3, 0.1, 0.03};

    private final List<Runup.Root> roots;
    int tried, kept;

    Tightener(List<Runup.Root> roots) {
        this.roots = roots;
    }

    static final class Replay {
        final List<PlayerInput> inputs = new ArrayList<PlayerInput>();
        /** The run before each move. */
        final List<Run> before = new ArrayList<Run>();
        int time = -1;
    }

    /** Plays the moves from {@code start} (not changed); stops early once the clock reaches {@code bound}. */
    static Replay replay(Runup.Root root, List<Move> moves, int bound) {
        Replay out = new Replay();
        out.inputs.addAll(root.inputs);
        Run r = root.run.copy();
        for (Move m : moves) {
            out.before.add(r.copy());
            m.play(r, out.inputs);
            if (r.dead || r.clock() >= bound) return out;
            if (r.done()) break;
        }
        if (r.done()) out.time = r.time();
        return out;
    }

    /** Time of moves[from..] played from {@code r} (changed), or -1. */
    private static int finish(Run r, List<Move> moves, int from, int bound) {
        List<PlayerInput> sink = new ArrayList<PlayerInput>();
        for (int i = from; i < moves.size(); i++) {
            moves.get(i).play(r, sink);
            if (r.dead || r.clock() >= bound) return -1;
            if (r.done()) return r.time();
        }
        return -1;
    }

    Planner.Plan tighten(Planner.Plan plan, long deadline) {
        Runup.Root root = plan.root;
        List<Move> moves = new ArrayList<Move>(plan.moves);
        Replay cur = replay(root, moves, Integer.MAX_VALUE);
        if (cur.time < 0) return plan;
        moves = new ArrayList<Move>(moves.subList(0, cur.before.size()));
        int best = cur.time;
        boolean improved = true;
        while (improved && System.currentTimeMillis() < deadline) {
            improved = false;
            for (Runup.Root r : roots) {
                if (r == root) continue;
                Replay x = replay(r, moves, best);
                tried++;
                if (x.time >= 0 && x.time < best) {
                    moves = new ArrayList<Move>(moves.subList(0, x.before.size()));
                    root = r;
                    best = x.time;
                    cur = x;
                    kept++;
                    improved = true;
                }
            }
            for (int i = 0; i < moves.size() && System.currentTimeMillis() < deadline; i++) {
                for (Move v : variants(moves.get(i))) {
                    List<Move> trial = new ArrayList<Move>(moves);
                    if (v == null) trial.remove(i);
                    else trial.set(i, v);
                    if (trial.isEmpty()) continue;
                    tried++;
                    int t = finish(cur.before.get(i).copy(), trial, i, Planner.DEBUG ? Integer.MAX_VALUE : best);
                    if (Planner.DEBUG) System.err.println("    move " + i + " " + v + ": " + t + " (best " + best + ")");
                    if (t >= 0 && t < best) {
                        cur = replay(root, trial, Integer.MAX_VALUE);
                        moves = new ArrayList<Move>(trial.subList(0, cur.before.size()));
                        if (cur.time != t) throw new IllegalStateException("replay gives " + cur.time + ", trial gave " + t);
                        best = t;
                        kept++;
                        improved = true;
                        if (i >= moves.size()) break;
                    }
                }
            }
        }
        return new Planner.Plan(root, moves, cur.inputs, best);
    }

    /** Changed versions of a move; null stands for dropping it. */
    private static List<Move> variants(Move m) {
        List<Move> out = new ArrayList<Move>();
        out.add(null);
        if (!(m instanceof Step)) return out;
        Step s = (Step) m;
        for (int dj = -2; dj <= 2; dj++) {
            int j = s.jumpAt == Step.NO_JUMP ? (dj > 0 ? dj - 1 : -1) : s.jumpAt + dj;
            if (dj == 0 || j < 0 && j != Step.NO_JUMP) continue;
            if (j == s.jumpAt) continue;
            out.add(s.withJump(j));
        }
        if (s.jumpAt != Step.NO_JUMP) out.add(s.withJump(Step.NO_JUMP));
        for (int st : StepManeuver.ALL_STRAFES) if (st != s.strafe) out.add(s.withStrafe(st));
        for (double d : NUDGES) {
            out.add(s.withAim(s.aimX + d, s.aimZ));
            out.add(s.withAim(s.aimX - d, s.aimZ));
            out.add(s.withAim(s.aimX, s.aimZ + d));
            out.add(s.withAim(s.aimX, s.aimZ - d));
        }
        return out;
    }
}
