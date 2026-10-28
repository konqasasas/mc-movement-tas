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
        "  mctas-search gap <problem.json> <run.tas>");

    public static void main(String[] args) throws IOException {
        if (args.length >= 2 && args[0].equals("bound")) {
            bound(args);
        } else if (args.length >= 2 && args[0].equals("speed")) {
            for (int i = 1; i < args.length; i++) speed(args[i]);
        } else if (args.length == 3 && args[0].equals("gap")) {
            gap(args[1], args[2]);
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
                int lb = Math.max(sb.ticksFrom(s.posX, s.box.minY, s.posZ, s.motionX, s.motionZ),
                    hb.ticksFromGround(s.posX, s.box.minY, s.posZ));
                int gap = left - lb;
                if (lb > left) System.out.println("  UNSOUND at tick " + t + ": bound " + lb + " > " + left + " left");
                if (last < 0 || t - last >= 20 || Math.abs(gap - lastGap) >= 10) {
                    System.out.printf("  tick %4d at (%.1f, %.1f, %.1f): %4d left, bound %4d, gap %4d%n", t - res.startTick, s.posX, s.box.minY, s.posZ, left, lb, gap);
                    last = t;
                    lastGap = gap;
                }
            }
            s = model.tick(s, in.get(t));
        }
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
