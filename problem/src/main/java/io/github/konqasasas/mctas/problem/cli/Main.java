package io.github.konqasasas.mctas.problem.cli;

import io.github.konqasasas.mctas.physics.EngineBuild;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.Region;
import io.github.konqasasas.mctas.problem.baseline.BeamSearch;
import io.github.konqasasas.mctas.problem.cli.importer.WorldDiff;
import io.github.konqasasas.mctas.problem.cli.importer.WorldImport;
import io.github.konqasasas.mctas.problem.Vec3;
import io.github.konqasasas.mctas.problem.input.TickInput;
import io.github.konqasasas.mctas.problem.io.InputText;
import io.github.konqasasas.mctas.problem.io.PkcExport;
import io.github.konqasasas.mctas.problem.io.ProblemIO;
import io.github.konqasasas.mctas.problem.io.SolutionIO;
import io.github.konqasasas.mctas.problem.judge.Evaluator;
import io.github.konqasasas.mctas.problem.judge.PhysicsModel;
import io.github.konqasasas.mctas.problem.judge.RunResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

public final class Main {
    private static final String USAGE = String.join("\n",
        "usage:",
        "  mctas-problem time <problem.json> <inputs.tas|solution.json> [--trace] [--strict] [--solution out.json] [--pkc out.json]",
        "  mctas-problem check <problem-dir>",
        "  mctas-problem score <problem-dir> <solution-dir>... [--markdown]",
        "  mctas-problem search <problem.json> <out.tas> [--beam N] [--horizon T] [--yaws a,b,c] [--via x,y,z;x,y,z]",
        WorldImport.USAGE,
        "  mctas-problem diffworld <a.problem.json> <b.problem.json>");

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println(USAGE);
            System.exit(2);
        }
        if (args[0].equals("time") && args.length >= 3) {
            System.exit(time(args));
        } else if (args[0].equals("search") && args.length >= 3) {
            System.exit(search(args));
        } else if (args[0].equals("check") && args.length == 2) {
            System.exit(check(Paths.get(args[1])));
        } else if (args[0].equals("score") && args.length >= 3) {
            System.exit(Score.run(args));
        } else if (args[0].equals("import")) {
            System.exit(WorldImport.run(args));
        } else if (args[0].equals("diffworld") && args.length == 3) {
            System.exit(WorldDiff.run(args));
        } else {
            System.err.println(USAGE);
            System.exit(2);
        }
    }

    private static int time(String[] args) throws IOException {
        Path problemFile = Paths.get(args[1]);
        Problem problem = ProblemIO.read(problemFile);
        List<TickInput> inputs = readInputs(Paths.get(args[2]), problem);
        boolean trace = false;
        boolean strict = false;
        Path solutionOut = null;
        Path pkcOut = null;
        for (int i = 3; i < args.length; i++) {
            if (args[i].equals("--trace")) trace = true;
            else if (args[i].equals("--strict")) strict = true;
            else if (args[i].equals("--solution")) solutionOut = Paths.get(args[++i]);
            else if (args[i].equals("--pkc")) pkcOut = Paths.get(args[++i]);
        }
        if (args[2].endsWith(".json")) {
            String warning = SolutionIO.buildMismatch(
                    SolutionIO.readEngineBuild(new String(Files.readAllBytes(Paths.get(args[2])), StandardCharsets.UTF_8)));
            if (warning != null) {
                System.err.println((strict ? "refused: " : "warning: ") + warning);
                if (strict) return 3;
            }
        }
        PhysicsModel model = new PhysicsModel(problem);
        RunResult r = Evaluator.run(problem, model, inputs);
        if (trace) {
            for (int t = 0; t < r.positions.size(); t++) {
                Vec3 p = r.positions.get(t);
                String mark = t == r.startTick ? "  <start" : t == r.goalTick && r.finished() ? "  <goal" : "";
                System.out.println("t=" + t + " pos=" + p + mark);
            }
        }
        System.out.println("problem: " + problem.id);
        System.out.println("engineBuild: " + EngineBuild.ID);
        System.out.println("status: " + r.status.name().toLowerCase());
        if (r.violation != null) System.out.println("violation: " + r.violation + " at tick " + r.violationTick + " (D-005)");
        if (r.finished()) {
            System.out.println("startTick: " + r.startTick);
            System.out.println("goalTick: " + r.goalTick);
            System.out.println("ticks: " + r.ticks() + " (" + (r.ticks() / 20.0) + " s)");
            if (problem.target != null) {
                System.out.println("target: " + problem.target.ticks + " (" + problem.target.kind.name().toLowerCase() + ")");
            }
        }
        if (solutionOut != null) {
            write(solutionOut, SolutionIO.write(problem, inputs, r, model.name()));
        }
        if (pkcOut != null) {
            List<TickInput> used = r.finished() ? inputs.subList(0, r.goalTick) : inputs;
            write(pkcOut, PkcExport.toPkcSave(problem, used, new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date())));
        }
        return r.finished() ? 0 : 1;
    }

    private static int check(Path dir) throws IOException {
        List<Path> files = new ArrayList<Path>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.problem.json")) {
            for (Path p : ds) files.add(p);
        }
        Collections.sort(files);
        int failures = 0;
        for (Path f : files) {
            Problem problem = ProblemIO.read(f);
            if (problem.target != null && problem.target.witness == null) {
                System.out.println(problem.id + ": target " + problem.target.ticks + " ("
                    + problem.target.kind.name().toLowerCase() + ", no witness)");
                continue;
            }
            if (problem.target == null) {
                System.out.println(problem.id + ": no target");
                continue;
            }
            List<TickInput> inputs = readInputs(f.resolveSibling(problem.target.witness), problem);
            RunResult r = Evaluator.run(problem, inputs);
            boolean ok = r.finished() && r.ticks() == problem.target.ticks;
            if (!ok) failures++;
            System.out.println(problem.id + ": " + (r.finished() ? r.ticks() + " ticks" : r.status.name().toLowerCase())
                + " target " + problem.target.ticks + (ok ? " ok" : " MISMATCH"));
        }
        return failures == 0 ? 0 : 1;
    }

    private static int search(String[] args) throws IOException {
        Problem problem = ProblemIO.read(Paths.get(args[1]));
        int beam = 3000;
        int horizon = 400;
        float[] yaws = {0.0F, 45.0F, -45.0F};
        List<Region> via = new ArrayList<Region>();
        for (int i = 3; i < args.length; i++) {
            if (args[i].equals("--beam")) beam = Integer.parseInt(args[++i]);
            else if (args[i].equals("--horizon")) horizon = Integer.parseInt(args[++i]);
            else if (args[i].equals("--yaws")) {
                String[] parts = args[++i].split(",");
                yaws = new float[parts.length];
                for (int j = 0; j < parts.length; j++) yaws[j] = Float.parseFloat(parts[j]);
            } else if (args[i].equals("--via")) {
                for (String b : args[++i].split(";")) {
                    String[] c = b.split(",");
                    int bx = Integer.parseInt(c[0]), by = Integer.parseInt(c[1]), bz = Integer.parseInt(c[2]);
                    via.add(new Region(bx - 0.3, by + 1, bz - 0.3, bx + 1.3, by + 1, bz + 1.3));
                }
            }
        }
        BeamSearch.Result r = new BeamSearch(problem, yaws, via, beam).run(horizon, 40);
        if (r == null || !r.run.finished()) {
            System.out.println(problem.id + ": no solution found");
            return 1;
        }
        System.out.println(problem.id + ": " + r.run.ticks() + " ticks (start " + r.run.startTick + ", goal " + r.run.goalTick + ")");
        String header = "# " + problem.id + ": " + r.run.ticks() + " ticks, found by baseline beam search\n";
        write(Paths.get(args[2]), header + InputText.format(r.inputs.subList(0, r.run.goalTick), problem.spawn));
        return 0;
    }

    static List<TickInput> readInputs(Path file, Problem problem) throws IOException {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        if (file.getFileName().toString().endsWith(".json")) return SolutionIO.readInputs(text);
        return InputText.parse(text, problem.spawn);
    }

    private static void write(Path p, String s) throws IOException {
        Files.write(p, s.getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote " + p);
    }
}
