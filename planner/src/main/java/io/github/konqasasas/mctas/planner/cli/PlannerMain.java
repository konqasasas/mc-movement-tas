package io.github.konqasasas.mctas.planner.cli;

import io.github.konqasasas.mctas.planner.GuideTool;
import io.github.konqasasas.mctas.planner.PlannerSolver;
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

public final class PlannerMain {
    private static final String USAGE = String.join("\n",
        "usage:",
        "  mctas-planner solve <problem.json|problem-dir> <out-dir> [--budget SECONDS] [--quiet] [--no-technique] [--guide MODEL [--guide-penalty TICKS]]",
        "",
        "  mctas-planner guide-data <problems-root> <out.csv> <solution.json|dir>...",
        "  mctas-planner guide-record <problem.json|problem-dir> <out.csv> [--budget SECONDS] [--min-tries N]",
        "  mctas-planner guide-train <out-model> <record.csv>... [--steps N] [--l2 X] [--holdout PREFIX]... [--drop PREFIX]...",
        "",
        "Writes <id>.solution.json (and a .tas copy) per problem into out-dir. The budget is per problem.",
        "guide-data and guide-record collect the learned guide's training data (WS13); report-only problems are refused.");

    public static void main(String[] args) throws IOException {
        if (args.length >= 4 && args[0].equals("guide-data")) {
            List<Path> sols = new ArrayList<Path>();
            for (int i = 3; i < args.length; i++) sols.add(Paths.get(args[i]));
            System.exit(GuideTool.data(Paths.get(args[1]), Paths.get(args[2]), sols));
        }
        if (args.length >= 3 && args[0].equals("guide-record")) {
            double budget = 30;
            int minTries = 10;
            for (int i = 3; i < args.length; i++) {
                if (args[i].equals("--budget")) budget = Double.parseDouble(args[++i]);
                else if (args[i].equals("--min-tries")) minTries = Integer.parseInt(args[++i]);
            }
            System.exit(GuideTool.record(problems(Paths.get(args[1])), Paths.get(args[2]), (long) (budget * 1000), minTries));
        }
        if (args.length >= 3 && args[0].equals("guide-train")) {
            System.exit(GuideTool.train(java.util.Arrays.asList(args).subList(1, args.length)));
        }
        if (args.length < 3 || !args[0].equals("solve")) {
            System.err.println(USAGE);
            System.exit(2);
        }
        Path in = Paths.get(args[1]);
        Path out = Paths.get(args[2]);
        double budget = 60;
        boolean quiet = false;
        boolean technique = true;
        Path guide = null;
        double guidePenalty = 10;
        for (int i = 3; i < args.length; i++) {
            if (args[i].equals("--budget")) budget = Double.parseDouble(args[++i]);
            else if (args[i].equals("--guide")) guide = Paths.get(args[++i]);
            else if (args[i].equals("--guide-penalty")) guidePenalty = Double.parseDouble(args[++i]);
            else if (args[i].equals("--quiet")) quiet = true;
            else if (args[i].equals("--no-technique")) technique = false;
            else {
                System.err.println(USAGE);
                System.exit(2);
            }
        }
        List<Path> files = problems(in);
        String engine = guide == null ? PlannerSolver.ENGINE : PlannerSolver.ENGINE + "+guide";
        Files.createDirectories(out);
        int failed = 0;
        for (Path f : files) {
            Problem problem = ProblemIO.read(f);
            long t0 = System.currentTimeMillis();
            System.out.println(problem.id + ":");
            PlannerSolver solver = new PlannerSolver(problem, (long) (budget * 1000)).segments(technique);
            if (guide != null) solver.guide(guide, guidePenalty);
            if (!quiet) solver.log(System.out);
            PlannerSolver.Solution sol = solver.solve();
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
            String json = SolutionIO.write(problem, sol.inputs, r, "physics-1.12.2", new SearchInfo(engine, ms));
            Files.write(out.resolve(base + ".solution.json"), json.getBytes(StandardCharsets.UTF_8));
            String tas = "# " + problem.id + ": " + r.ticks() + " ticks, found by " + engine + "\n"
                + InputText.format(sol.inputs.subList(0, r.goalTick), problem.spawn);
            Files.write(out.resolve(base + ".tas"), tas.getBytes(StandardCharsets.UTF_8));
        }
        System.exit(failed == 0 ? 0 : 1);
    }

    private static List<Path> problems(Path in) throws IOException {
        List<Path> files = new ArrayList<Path>();
        if (Files.isDirectory(in)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(in, "*.problem.json")) {
                for (Path p : ds) files.add(p);
            }
            Collections.sort(files);
        } else {
            files.add(in);
        }
        return files;
    }
}
