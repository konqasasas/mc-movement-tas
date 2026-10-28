package io.github.konqasasas.mctas.search.cli;

import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.io.ProblemIO;
import io.github.konqasasas.mctas.problem.io.InputText;
import io.github.konqasasas.mctas.search.bound.LowerBound;
import io.github.konqasasas.mctas.search.exact.Beam;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import java.io.IOException;
import java.nio.file.Paths;

public final class Main {
    private static final String USAGE = String.join("\n",
        "usage:",
        "  mctas-search bound [--cell S] [--cap N] [--trace] <problem.json>...",
        "  mctas-search beam <problem.json> [--width N] [--out file.tas]");

    public static void main(String[] args) throws IOException {
        if (args.length >= 2 && args[0].equals("bound")) {
            bound(args);
        } else if (args.length >= 2 && args[0].equals("beam")) {
            beam(args);
        } else {
            System.err.println(USAGE);
            System.exit(2);
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
        double cell = 0;
        int cap = 16;
        boolean trace = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--cell")) cell = Double.parseDouble(args[++i]);
            else if (args[i].equals("--cap")) cap = Integer.parseInt(args[++i]);
            else if (args[i].equals("--trace")) trace = true;
            else bound(args[i], cell, cap, trace);
        }
    }

    private static void bound(String file, double cell, int cap, boolean trace) throws IOException {
        Problem problem = ProblemIO.read(Paths.get(file));
        long t0 = System.nanoTime();
        LowerBound lb = new LowerBound(problem);
        if (trace) lb.trace = new StringBuilder();
        lb.cap = cap;
        lb.cellStep = cell;
        LowerBound.Result r = lb.run(problem.maxTicks);
        if (lb.trace != null) System.out.print(lb.trace);
        long ms = (System.nanoTime() - t0) / 1000000;
        String target = problem.target == null ? "" : " (target " + problem.target.ticks + ")";
        if (r == null) System.out.println(problem.id + ": unreachable within " + problem.maxTicks + " ticks" + target);
        else System.out.println(problem.id + ": >= " + r.ticks + " ticks" + target + "  [" + r.preStates + " pre, "
            + r.entryStates + " entry, " + r.work + " steps, " + ms + " ms]");
    }
}
