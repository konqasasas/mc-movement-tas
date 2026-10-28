package io.github.konqasasas.mctas.approx.cli;

import io.github.konqasasas.mctas.approx.ApproxSolver;
import io.github.konqasasas.mctas.approx.CourseGen;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.io.InputText;
import io.github.konqasasas.mctas.problem.io.ProblemIO;
import io.github.konqasasas.mctas.problem.io.SearchInfo;
import io.github.konqasasas.mctas.problem.io.SolutionIO;
import io.github.konqasasas.mctas.problem.judge.Evaluator;
import io.github.konqasasas.mctas.problem.judge.RunResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ApproxMain {
    private static final String USAGE = String.join("\n",
        "usage:",
        "  mctas-approx solve <problem.json|problem-dir> <out-dir> [--budget SECONDS] [--quiet] [--fixed-yaw]",
        "  mctas-approx gen <out-dir> <set-name> <first-seed> <count> [--full]",
        "",
        "Writes <id>.solution.json (and a .tas copy) per problem into out-dir. The budget is per problem.");

    public static void main(String[] args) throws IOException {
        if ((args.length == 5 || args.length == 6 && args[5].equals("--full")) && args[0].equals("gen")) {
            boolean full = args.length == 6;
            Path out = Paths.get(args[1]);
            Files.createDirectories(out);
            long first = Long.parseLong(args[3]);
            int count = Integer.parseInt(args[4]);
            for (long seed = first; seed < first + count; seed++) {
                int jumps = 5 + (int) (seed % 8);
                String name = String.format("s%04d-%dj", seed, jumps);
                String text = CourseGen.generate(args[2] + "/" + name, seed, jumps, full);
                Files.write(out.resolve(name + ".problem.json"), text.getBytes(StandardCharsets.UTF_8));
                System.out.println("wrote " + out.resolve(name + ".problem.json"));
            }
            return;
        }
        if (args.length < 3 || !args[0].equals("solve")) {
            System.err.println(USAGE);
            System.exit(2);
        }
        Path in = Paths.get(args[1]);
        Path out = Paths.get(args[2]);
        double budget = 30;
        boolean quiet = false;
        boolean fixedYaw = false;
        for (int i = 3; i < args.length; i++) {
            if (args[i].equals("--budget")) budget = Double.parseDouble(args[++i]);
            else if (args[i].equals("--quiet")) quiet = true;
            else if (args[i].equals("--fixed-yaw")) fixedYaw = true;
            else {
                System.err.println(USAGE);
                System.exit(2);
            }
        }
        List<Path> files = new ArrayList<Path>();
        if (Files.isDirectory(in)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(in, "*.problem.json")) {
                for (Path p : ds) files.add(p);
            }
            Collections.sort(files);
        } else {
            files.add(in);
        }
        Files.createDirectories(out);
        int failed = 0;
        for (Path f : files) {
            Problem problem = ProblemIO.read(f);
            long t0 = System.currentTimeMillis();
            ApproxSolver solver = new ApproxSolver(problem, (long) (budget * 1000)).fixedYawLanes(fixedYaw);
            if (!quiet) solver.log(System.out);
            ApproxSolver.Solution sol = solver.solve();
            long ms = System.currentTimeMillis() - t0;
            String base = problem.id.replace('/', '_');
            if (sol == null) {
                failed++;
                System.out.println(problem.id + ": no solution (" + ms + " ms)");
                continue;
            }
            RunResult r = Evaluator.run(problem, sol.inputs);
            System.out.println(problem.id + ": " + r.ticks() + " ticks, best found after " + sol.foundMillis + " ms ("
                + ms + " ms total)" + (problem.target != null ? ", target " + problem.target.ticks : ""));
            String json = SolutionIO.write(problem, sol.inputs, r, "physics-1.12.2", new SearchInfo(ApproxSolver.ENGINE, ms));
            Files.write(out.resolve(base + ".solution.json"), json.getBytes(StandardCharsets.UTF_8));
            String tas = "# " + problem.id + ": " + r.ticks() + " ticks, found by " + ApproxSolver.ENGINE + "\n"
                + InputText.format(sol.inputs.subList(0, r.goalTick), problem.spawn);
            Files.write(out.resolve(base + ".tas"), tas.getBytes(StandardCharsets.UTF_8));
        }
        System.exit(failed == 0 ? 0 : 1);
    }
}
