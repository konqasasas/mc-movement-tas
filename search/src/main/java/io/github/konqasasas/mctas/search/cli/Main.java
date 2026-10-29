package io.github.konqasasas.mctas.search.cli;

import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.io.ProblemIO;
import io.github.konqasasas.mctas.problem.io.InputText;
import io.github.konqasasas.mctas.search.bound.HopBound;
import io.github.konqasasas.mctas.search.bound.LowerBound;
import io.github.konqasasas.mctas.search.bound.SpeedBound;
import io.github.konqasasas.mctas.search.exact.Beam;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import java.io.IOException;
import java.nio.file.Paths;

public final class Main {
    private static final String USAGE = String.join("\n",
        "usage:",
        "  mctas-search bound [--cell S] [--zcell S] [--cap N] [--max-steps N] [--trace] <problem.json>...",
        "  mctas-search beam <problem.json> [--width N] [--out file.tas]",
        "  mctas-search speed <problem.json>...",
        "  mctas-search gap <problem.json> <run.tas>",
        "  mctas-search segments <problem.json> <run.tas> [--length N] [--json out.json]");

    public static void main(String[] args) throws IOException {
        if (args.length >= 2 && args[0].equals("bound")) {
            bound(args);
        } else if (args.length >= 2 && args[0].equals("speed")) {
            for (int i = 1; i < args.length; i++) speed(args[i]);
        } else if (args.length == 3 && args[0].equals("gap")) {
            gap(args[1], args[2]);
        } else if (args.length >= 3 && args[0].equals("segments")) {
            segments(args);
        } else if (args.length >= 2 && args[0].equals("beam")) {
            beam(args);
        } else {
            System.err.println(USAGE);
            System.exit(2);
        }
    }

    private static void speed(String file) throws IOException {
        Problem problem = ProblemIO.read(Paths.get(file));
        long t0 = System.nanoTime();
        SpeedBound sb = new SpeedBound(problem);
        int speed = sb.ticks(problem.maxTicks);
        long t1 = System.nanoTime();
        HopBound hb = new HopBound(problem);
        int hops = hb.ticks();
        long ms0 = (t1 - t0) / 1000000, ms1 = (System.nanoTime() - t1) / 1000000;
        String target = problem.target == null ? "" : " (target " + problem.target.ticks + ")";
        String hopText = hops >= HopBound.INF ? "unreachable" : Integer.toString(hops);
        System.out.println(problem.id + ": >= " + Math.max(speed, Math.min(hops, problem.maxTicks + 1)) + " ticks" + target
            + "  [speed " + speed + " (" + sb + ", " + ms0 + " ms); hops " + hopText + " (" + hb + ", " + ms1 + " ms)]");
    }

    /**
     * Replays a run and prints, at each landing, the ticks it still took against the lower bound from
     * there; a bound above the run would be a soundness bug and is flagged.
     */
    private static void gap(String problemFile, String tasFile) throws IOException {
        Problem problem = ProblemIO.read(Paths.get(problemFile));
        String text = new String(Files.readAllBytes(Paths.get(tasFile)), StandardCharsets.UTF_8);
        java.util.List<io.github.konqasasas.mctas.problem.input.TickInput> in = InputText.parse(text, problem.spawn);
        io.github.konqasasas.mctas.problem.judge.RunResult res = io.github.konqasasas.mctas.problem.judge.Evaluator.run(problem, in);
        if (!res.finished()) {
            System.out.println(problem.id + ": the run does not finish (" + res.status + ")");
            return;
        }
        SpeedBound sb = new SpeedBound(problem);
        HopBound hb = new HopBound(problem);
        int whole = Math.max(sb.ticks(problem.maxTicks), hb.ticks());
        System.out.println(problem.id + ": run " + res.ticks() + " ticks, lower bound " + whole + ", gap " + (res.ticks() - whole));
        if (System.getenv("MCTAS_PATH") != null) {
            for (String line : hb.path()) System.out.println("  hop " + line);
        }
        io.github.konqasasas.mctas.search.exact.ExactModel model = new io.github.konqasasas.mctas.search.exact.ExactModel(problem);
        io.github.konqasasas.mctas.physics.PlayerState s = model.spawn(problem);
        int last = -1, lastGap = res.ticks() - whole;
        for (int t = 0; t < res.goalTick; t++) {
            if (t >= res.startTick && s.onGround) {
                int left = res.goalTick - t;
                int speedLb = sb.ticksFrom(s.posX, s.box.minY, s.posZ, s.motionX, s.motionZ);
                int hopLb = hb.ticksFromGround(s.posX, s.box.minY, s.posZ);
                int lb = Math.max(speedLb, hopLb);
                int gap = left - lb;
                if (lb > left) {
                    System.out.printf("  UNSOUND at tick %d (%.4f, %.4f, %.4f) motion (%.4f, %.4f, %.4f): bound %d (speed %d, hops %d) > %d left%n",
                        t, s.posX, s.box.minY, s.posZ, s.motionX, s.motionY, s.motionZ, lb, speedLb, hopLb, left);
                }
                if (last < 0 || t - last >= 20 || Math.abs(gap - lastGap) >= 10) {
                    System.out.printf("  tick %4d at (%.1f, %.1f, %.1f): %4d left, bound %4d, gap %4d%n", t - res.startTick, s.posX, s.box.minY, s.posZ, left, lb, gap);
                    last = t;
                    lastGap = gap;
                }
            }
            s = model.tick(s, in.get(t));
        }
    }

    /**
     * Cuts a run at landings into pieces of about {@code --length} ticks (40 by default) and prints, for
     * each, the ticks it took against the lower bound from its first state (on the ground, at its
     * speed) to a small box round its last one (the goal for the last piece). The difference is the
     * most a better run can save there; 0 means the piece cannot get shorter. {@code --json} also
     * writes the pieces as a JSON array.
     */
    private static void segments(String[] args) throws IOException {
        Problem problem = ProblemIO.read(Paths.get(args[1]));
        String text = new String(Files.readAllBytes(Paths.get(args[2])), StandardCharsets.UTF_8);
        int length = 40;
        String json = null;
        for (int i = 3; i < args.length; i++) {
            if (args[i].equals("--length")) length = Integer.parseInt(args[++i]);
            else if (args[i].equals("--json")) json = args[++i];
        }
        java.util.List<io.github.konqasasas.mctas.problem.input.TickInput> in = InputText.parse(text, problem.spawn);
        io.github.konqasasas.mctas.problem.judge.RunResult res = io.github.konqasasas.mctas.problem.judge.Evaluator.run(problem, in);
        if (!res.finished()) {
            System.out.println(problem.id + ": the run does not finish (" + res.status + ")");
            return;
        }
        io.github.konqasasas.mctas.search.exact.ExactModel model = new io.github.konqasasas.mctas.search.exact.ExactModel(problem);
        java.util.List<io.github.konqasasas.mctas.physics.PlayerState> path = new java.util.ArrayList<io.github.konqasasas.mctas.physics.PlayerState>();
        io.github.konqasasas.mctas.physics.PlayerState s = model.spawn(problem);
        for (int t = 0; t <= res.goalTick; t++) {
            path.add(s);
            if (t < res.goalTick) s = model.tick(s, in.get(t));
        }
        // Cut points: the start, then the first landing at least `length` ticks after the last cut, then the goal.
        java.util.List<Integer> cuts = new java.util.ArrayList<Integer>();
        cuts.add(res.startTick);
        for (int t = res.startTick + 1; t < res.goalTick; t++) {
            if (path.get(t).onGround && t - cuts.get(cuts.size() - 1) >= length && res.goalTick - t >= length / 2) cuts.add(t);
        }
        cuts.add(res.goalTick);
        StringBuilder out = new StringBuilder("[\n");
        int total = 0, saved = 0;
        System.out.println(problem.id + ": run " + res.ticks() + " ticks in " + (cuts.size() - 1) + " pieces");
        for (int k = 0; k + 1 < cuts.size(); k++) {
            int a = cuts.get(k), b = cuts.get(k + 1);
            io.github.konqasasas.mctas.physics.PlayerState p = path.get(a), q = path.get(b);
            io.github.konqasasas.mctas.problem.Region from = point(p.posX, p.box.minY, p.posZ);
            io.github.konqasasas.mctas.problem.Region to = b == res.goalTick ? problem.goal : point(q.posX, q.box.minY, q.posZ);
            double speed = p.onGround ? Math.hypot(p.motionX, p.motionZ) : -1;
            Problem piece = new Problem(problem.id + "@" + a, "", problem.world,
                new io.github.konqasasas.mctas.problem.Spawn(new io.github.konqasasas.mctas.problem.Vec3(p.posX, p.posY, p.posZ), 0, 0),
                from, to, b - a + 1, problem.voidY, null, problem.inventory, problem.selectedSlot);
            int lb = new SpeedBound(piece).ticksFrom(p.posX, p.box.minY, p.posZ, p.motionX, p.motionZ);
            int hops = new HopBound(piece, from, to, speed).ticks();
            if (hops < HopBound.INF) lb = Math.max(lb, hops);
            int took = b - a;
            total += lb;
            saved += took - lb;
            System.out.printf("  ticks %4d..%4d at (%.1f, %.1f, %.1f) -> (%.1f, %.1f, %.1f): took %3d, bound %3d, slack %3d%s%n",
                a - res.startTick, b - res.startTick, p.posX, p.box.minY, p.posZ, q.posX, q.box.minY, q.posZ, took, lb, took - lb,
                lb > took ? "  UNSOUND" : "");
            out.append(String.format(java.util.Locale.ROOT,
                "  {\"from_tick\": %d, \"to_tick\": %d, \"took\": %d, \"bound\": %d, \"slack\": %d, \"from\": [%.4f, %.4f, %.4f], \"to\": [%.4f, %.4f, %.4f], \"to_goal\": %b}%s%n",
                a - res.startTick, b - res.startTick, took, lb, took - lb, p.posX, p.box.minY, p.posZ, q.posX, q.box.minY, q.posZ,
                b == res.goalTick, k + 2 < cuts.size() ? "," : ""));
        }
        out.append("]\n");
        System.out.println("  sum of piece bounds " + total + ", slack " + saved);
        if (json != null) Files.write(Paths.get(json), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static io.github.konqasasas.mctas.problem.Region point(double x, double y, double z) {
        return new io.github.konqasasas.mctas.problem.Region(x - 1e-3, y - 1e-3, z - 1e-3, x + 1e-3, y + 1e-3, z + 1e-3);
    }

    private static void beam(String[] args) throws IOException {
        Problem problem = ProblemIO.read(Paths.get(args[1]));
        int width = 2000;
        String out = null;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--width")) width = Integer.parseInt(args[++i]);
            else if (args[i].equals("--out")) out = args[++i];
        }
        long t0 = System.nanoTime();
        Beam beam = new Beam(problem, width);
        Beam.Result r = beam.run(problem.maxTicks);
        long ms = (System.nanoTime() - t0) / 1000000;
        String target = problem.target == null ? "" : " (target " + problem.target.ticks + ")";
        if (r == null || !r.run.finished()) {
            System.out.println(problem.id + ": no run found" + target + " [" + ms + " ms]");
            return;
        }
        System.out.println(problem.id + ": " + r.run.ticks() + " ticks" + target + ", start " + r.run.startTick
            + " goal " + r.run.goalTick + ", route " + beam.waypoints().size() + " [" + ms + " ms]");
        if (out != null) {
            String text = "# " + problem.id + ": " + r.run.ticks() + " ticks (beam search on the exact physics)\n"
                + InputText.format(r.inputs.subList(0, r.run.goalTick), problem.spawn);
            Files.write(Paths.get(out), text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void bound(String[] args) throws IOException {
        double cell = 0, zcell = 0;
        int cap = 16;
        boolean trace = false;
        long maxSteps = 20000000L;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--cell")) cell = Double.parseDouble(args[++i]);
            else if (args[i].equals("--zcell")) zcell = Double.parseDouble(args[++i]);
            else if (args[i].equals("--cap")) cap = Integer.parseInt(args[++i]);
            else if (args[i].equals("--max-steps")) maxSteps = Long.parseLong(args[++i]);
            else if (args[i].equals("--trace")) trace = true;
            else bound(args[i], cell, zcell, cap, maxSteps, trace);
        }
    }

    private static void bound(String file, double cell, double zcell, int cap, long maxSteps, boolean trace) throws IOException {
        Problem problem = ProblemIO.read(Paths.get(file));
        long t0 = System.nanoTime();
        LowerBound lb = new LowerBound(problem);
        if (trace) lb.trace = new StringBuilder();
        lb.cap = cap;
        lb.cellStep = cell;
        lb.zCellStep = zcell;
        lb.maxSteps = maxSteps;
        LowerBound.Result r = lb.run(problem.maxTicks);
        if (lb.trace != null) System.out.print(lb.trace);
        long ms = (System.nanoTime() - t0) / 1000000;
        String target = problem.target == null ? "" : " (target " + problem.target.ticks + ")";
        if (r == null) System.out.println(problem.id + ": unreachable within " + problem.maxTicks + " ticks" + target);
        else System.out.println(problem.id + ": >= " + r.ticks + " ticks" + target + "  [" + r.preStates + " pre, "
            + r.entryStates + " entry, " + r.work + " steps, " + ms + " ms]");
    }
}
