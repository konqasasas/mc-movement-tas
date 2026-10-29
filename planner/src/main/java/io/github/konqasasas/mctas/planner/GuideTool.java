package io.github.konqasasas.mctas.planner;

import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.input.TickInput;
import io.github.konqasasas.mctas.problem.io.ProblemIO;
import io.github.konqasasas.mctas.problem.io.SolutionIO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** The command-line side of the learned guide (WS13): collecting its data and fitting it. */
public final class GuideTool {
    private GuideTool() {
    }

    /**
     * Problems kept for reporting only (D-501, L-015): never training data. human5, waza3, the
     * generated holdouts and v1-holdout.
     */
    public static boolean reportOnly(String id) {
        return id.matches("human6/(remaining|rampart|poseidon|prism|tohell)") || id.matches("(waza/)?waza3.*")
            || id.startsWith("gen-holdout/") || id.startsWith("gen-dev2-holdout/") || id.startsWith("v1-holdout/");
    }

    /** Problem files under {@code root} by id, and by the last part of the id. */
    static Map<String, Path> index(Path root) throws IOException {
        Map<String, Path> out = new HashMap<String, Path>();
        List<Path> files = new ArrayList<Path>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(p -> p.toString().endsWith(".problem.json") && !p.toString().contains("/build/")).forEach(files::add);
        }
        for (Path p : files) {
            String id = ProblemIO.read(p).id;
            out.put(id, p);
            out.putIfAbsent(id.substring(id.lastIndexOf('/') + 1), p);
        }
        return out;
    }

    /** {@code guide-data}: the moves between platforms of the given solutions, as CSV. */
    public static int data(Path problems, Path out, List<Path> solutions) throws IOException {
        Map<String, Path> index = index(problems);
        List<Path> files = new ArrayList<Path>();
        for (Path s : solutions) {
            if (Files.isDirectory(s)) {
                try (Stream<Path> w = Files.walk(s)) {
                    w.filter(p -> p.toString().endsWith(".solution.json")).sorted().forEach(files::add);
                }
            } else {
                files.add(s);
            }
        }
        StringBuilder csv = new StringBuilder(GuideData.header()).append('\n');
        int used = 0;
        for (Path f : files) {
            String json = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            String id = SolutionIO.readProblemId(json);
            Path p = index.get(id);
            if (p == null) p = index.get(id.substring(id.lastIndexOf('/') + 1));
            if (p == null) {
                System.err.println(f + ": no problem " + id);
                continue;
            }
            Problem problem = ProblemIO.read(p);
            if (reportOnly(problem.id)) {
                System.err.println(f + ": " + problem.id + " is for reporting only, skipped");
                continue;
            }
            List<TickInput> in = SolutionIO.readInputs(json);
            if (Boolean.getBoolean("guide.ranks")) {
                for (int targets : new int[] {4, 10}) {
                    List<int[]> rk = GuideData.aimRanks(problem, in, targets);
                    if (rk == null) continue;
                    StringBuilder b = new StringBuilder();
                    for (int[] x : rk) b.append(x[0]).append(' ');
                    System.out.println("ranks " + targets + " " + problem.id + " " + b);
                }
            }
            List<GuideData.Sample> ss = GuideData.fromSolution(problem, in);
            if (ss == null) {
                System.err.println(f + ": does not replay to the goal, skipped");
                continue;
            }
            used++;
            for (GuideData.Sample x : ss) csv.append(x.csv()).append('\n');
            System.err.println(f + ": " + problem.id + ", " + ss.size() + " moves");
        }
        Files.write(out, csv.toString().getBytes(StandardCharsets.UTF_8));
        System.err.println(used + " of " + files.size() + " solutions used");
        return 0;
    }

    /** {@code guide-record}: runs the planner on each problem and writes what its A* aimed at and made. */
    public static int record(List<Path> problems, Path out, long budgetMillis, int minTries) throws IOException {
        StringBuilder csv = new StringBuilder(GuideData.header()).append('\n');
        for (Path p : problems) {
            Problem problem = ProblemIO.read(p);
            if (reportOnly(problem.id)) {
                System.err.println(problem.id + " is for reporting only, skipped");
                continue;
            }
            GuideData.Recorder rec = new GuideData.Recorder();
            PlannerSolver solver = new PlannerSolver(problem, budgetMillis).segments(false).record(rec);
            PlannerSolver.Solution sol;
            try {
                sol = solver.solve();
            } catch (RuntimeException e) {
                System.err.println(problem.id + ": " + e);
                continue;
            }
            List<GuideData.Sample> ss = rec.samples(solver.course(), problem.id, minTries);
            int made = 0;
            for (GuideData.Sample x : ss) {
                csv.append(x.csv()).append('\n');
                made += x.made;
            }
            System.err.println(problem.id + ": " + (sol == null ? "no solution" : sol.ticks + " ticks") + ", " + ss.size() + " pairs, " + made + " made");
        }
        Files.write(out, csv.toString().getBytes(StandardCharsets.UTF_8));
        return 0;
    }

    /**
     * {@code guide-train}: fits the guide's model to the pairs of platforms of {@code guide-record} and
     * writes it. Problems whose id starts with a {@code --holdout} prefix are only scored, and those
     * with a {@code --drop} prefix are left out.
     */
    public static int train(List<String> args) throws IOException {
        Path out = Paths.get(args.get(0));
        List<Path> csvs = new ArrayList<Path>();
        List<String> holdout = new ArrayList<String>(), drop = new ArrayList<String>();
        int steps = 2000;
        double l2 = 1e-3;
        for (int i = 1; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals("--steps")) steps = Integer.parseInt(args.get(++i));
            else if (a.equals("--l2")) l2 = Double.parseDouble(args.get(++i));
            else if (a.equals("--holdout")) holdout.add(args.get(++i));
            else if (a.equals("--drop")) drop.add(args.get(++i));
            else csvs.add(Paths.get(a));
        }
        List<double[]> tf = new ArrayList<double[]>(), vf = new ArrayList<double[]>();
        List<Integer> ty = new ArrayList<Integer>(), vy = new ArrayList<Integer>();
        for (Path csv : csvs) {
            List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
            if (!lines.get(0).equals(GuideData.header())) throw new IllegalArgumentException(csv + ": not a guide-record file");
            for (int li = 1; li < lines.size(); li++) {
                String[] p = lines.get(li).split(",");
                String problem = p[0];
                if (reportOnly(problem)) throw new IllegalArgumentException(problem + " is for reporting only");
                boolean skip = false, val = false;
                for (String d : drop) skip |= problem.startsWith(d);
                for (String h : holdout) val |= problem.startsWith(h);
                if (skip) continue;
                double[] f = new double[GuideFeatures.N];
                for (int i = 0; i < f.length; i++) f[i] = Double.parseDouble(p[i + 1]);
                (val ? vf : tf).add(f);
                (val ? vy : ty).add(Integer.parseInt(p[GuideFeatures.N + 1]));
            }
        }
        System.err.println(tf.size() + " training moves (" + share(ty) + " made), " + vf.size() + " held out (" + share(vy) + " made)");
        GuideModel m = GuideModel.fit(tf, ty, steps, l2);
        double[] tr = m.evaluate(tf, ty);
        System.err.printf(java.util.Locale.ROOT, "train: log-likelihood %.3f, right %.3f%n", tr[0], tr[1]);
        if (!vf.isEmpty()) {
            double[] va = m.evaluate(vf, vy);
            System.err.printf(java.util.Locale.ROOT, "held out: log-likelihood %.3f, right %.3f%n", va[0], va[1]);
        }
        Files.write(out, m.write().getBytes(StandardCharsets.UTF_8));
        return 0;
    }

    private static String share(List<Integer> y) {
        int s = 0;
        for (int v : y) s += v;
        return String.format(java.util.Locale.ROOT, "%.3f", s / (double) Math.max(1, y.size()));
    }
}
