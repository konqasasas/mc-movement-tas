package io.github.konqasasas.mctas.improve.cli;

import io.github.konqasasas.mctas.improve.Course;
import io.github.konqasasas.mctas.improve.Improver;
import io.github.konqasasas.mctas.improve.Run;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.input.Key;
import io.github.konqasasas.mctas.problem.input.TickInput;
import io.github.konqasasas.mctas.problem.io.InputText;
import io.github.konqasasas.mctas.problem.io.ProblemIO;
import io.github.konqasasas.mctas.problem.io.SearchInfo;
import io.github.konqasasas.mctas.problem.io.SolutionIO;
import io.github.konqasasas.mctas.problem.judge.Evaluator;
import io.github.konqasasas.mctas.problem.judge.PhysicsModel;
import io.github.konqasasas.mctas.problem.judge.RunResult;
import io.github.konqasasas.mctas.problem.judge.ToggleRule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class ImproveMain {
    public static final String ENGINE = "improve-lns";

    private ImproveMain() {
    }

    private static final String USAGE = String.join("\n",
            "usage:",
            "  mctas-improve run <problem.json|problem-dir> --seeds <file|dir>[,...] --out <dir> [--budget seconds]",
            "        [--threads N] [--rng N] [--width N] [--window N] [--shares micro,rebuild,cma,cross] [--fixed-shares] [--no-toggle-fix] [--no-drift] [--focus p] [--rejump p] [--land p] [--quiet]",
            "    Improves the seed runs of each problem for --budget seconds (default 60) and writes",
            "    <id with / as _>.solution.json and .tas to --out. Seeds are .tas or .solution.json files; in a",
            "    directory, a .solution.json is matched by the problem id inside, a .tas by its file name",
            "    (<id with / as _>.tas or <problem file name>.tas). The problem's own target witness is a seed too.");

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !args[0].equals("run")) {
            System.err.println(USAGE);
            System.exit(2);
        }
        List<Path> problems = new ArrayList<Path>();
        List<Path> seeds = new ArrayList<Path>();
        Path out = null;
        double budget = 60;
        boolean quiet = false;
        Improver.Options o = new Improver.Options();
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--seeds":
                    for (String s : args[++i].split(",")) seeds.add(Paths.get(s));
                    break;
                case "--out": out = Paths.get(args[++i]); break;
                case "--budget": budget = Double.parseDouble(args[++i]); break;
                case "--threads": o.threads = Integer.parseInt(args[++i]); break;
                case "--rng": o.seed = Long.parseLong(args[++i]); break;
                case "--width": o.maxWidth = Integer.parseInt(args[++i]); break;
                case "--window": o.maxWindow = Integer.parseInt(args[++i]); break;
                case "--shares": {
                    String[] p = args[++i].split(",");
                    for (int k = 0; k < p.length; k++) o.shares[k] = Double.parseDouble(p[k]);
                    break;
                }
                case "--quiet": quiet = true; break;
                case "--fixed-shares": o.adaptive = false; break;
                case "--no-toggle-fix": o.fixToggles = false; break;
                case "--no-drift": o.useDrift = false; break;
                case "--focus": o.focusChance = Double.parseDouble(args[++i]); break;
                case "--rejump": o.rejumpChance = Double.parseDouble(args[++i]); break;
                case "--land": o.landChance = Double.parseDouble(args[++i]); break;
                case "--land-exit": o.landExitChance = Double.parseDouble(args[++i]); break;
                default:
                    if (a.startsWith("--")) throw new IllegalArgumentException("unknown option " + a);
                    Path p = Paths.get(a);
                    if (Files.isDirectory(p)) problems.addAll(list(p, "*.problem.json"));
                    else problems.add(p);
            }
        }
        if (out == null) throw new IllegalArgumentException("--out is required");
        Files.createDirectories(out);
        int failures = 0;
        for (Path pf : problems) {
            if (!improve(pf, seeds, out, budget, o, quiet)) failures++;
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    private static boolean improve(Path pf, List<Path> seedPaths, Path out, double budget, Improver.Options o,
                                   boolean quiet) throws Exception {
        long t0 = System.nanoTime();
        Problem p = ProblemIO.read(pf);
        Course course = new Course(p);
        Improver imp = new Improver(course, o);
        String stem = p.id.replace('/', '_');
        List<Path> seeds = findSeeds(p, pf, seedPaths);
        Run first = null;
        // Judge every seed before any of them sets the run-up limit.
        List<Run> judged = new ArrayList<Run>();
        for (Path s : seeds) {
            List<TickInput> in = readInputs(s, p);
            PlayerInput[] arr = new PlayerInput[in.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = PhysicsModel.toPhysics(in.get(i));
            Run r = course.evaluate(arr);
            Run m = Improver.mend(course, r, o.seed);
            if (m != r) System.out.println(p.id + ": seed " + s + ": " + r + "; mended: " + m);
            r = m;
            judged.add(r);
            System.out.println(p.id + ": seed " + s + ": " + r);
            if (r.valid() && r.betterThan(first)) first = r;
        }
        for (Run r : judged) imp.seed(r);
        if (first == null) {
            System.out.println(p.id + ": no valid seed, skipped");
            return false;
        }
        final Run seedBest = first;
        imp.onBest(r -> {
            if (!quiet) {
                System.out.printf("%s: %.1f s  %d ticks (%s)%n", p.id, (System.nanoTime() - t0) / 1e9, r.ticks(),
                        imp.lastBestMove());
            }
            try {
                write(p, r, out, stem, System.nanoTime() - t0);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        write(p, seedBest, out, stem, System.nanoTime() - t0);
        Run best = imp.run(t0 + (long) (budget * 1e9));
        long nanos = System.nanoTime() - t0;
        String check = write(p, best, out, stem, nanos);
        Improver.Stats st = imp.stats();
        StringBuilder sb = new StringBuilder();
        for (Improver.Move m : Improver.Move.values()) {
            int k = m.ordinal();
            sb.append(String.format(" %s %d calls %.0f s %d bests;", m.name().toLowerCase(), st.calls[k], st.nanos[k] / 1e9,
                    st.bests[k]));
        }
        System.out.printf("%s: %d -> %d ticks in %.1f s (%s)%n  moves:%s%n  seed %s; best %s%n", p.id, seedBest.ticks(),
                best.ticks(), nanos / 1e9, check, sb, Improver.landings(seedBest), Improver.landings(best));
        return true;
    }

    /** Writes the solution and the .tas, judged again by the problem module; returns the judge's verdict. */
    private static synchronized String write(Problem p, Run r, Path out, String stem, long nanos) throws IOException {
        List<TickInput> in = new ArrayList<TickInput>();
        for (PlayerInput x : r.inputs()) in.add(toTick(x));
        RunResult rr = Evaluator.run(p, in);
        List<Integer> v = ToggleRule.violations(p, in);
        if (!rr.finished() || rr.ticks() != r.ticks() || !v.isEmpty()) {
            throw new IllegalStateException(p.id + ": the judge disagrees: " + rr.status + " toggles " + v);
        }
        Files.write(out.resolve(stem + ".solution.json"), SolutionIO.write(p, in, rr, "physics-1.12.2",
                new SearchInfo(ENGINE, nanos / 1_000_000)).getBytes(StandardCharsets.UTF_8));
        String header = "# " + p.id + ": " + rr.ticks() + " ticks, " + ENGINE + "\n";
        Files.write(out.resolve(stem + ".tas"), (header + InputText.format(in, p.spawn)).getBytes(StandardCharsets.UTF_8));
        return "judge: " + rr.ticks() + " ticks, toggle rule ok";
    }

    public static TickInput toTick(PlayerInput a) {
        Set<Key> k = EnumSet.noneOf(Key.class);
        if (a.has(PlayerInput.W)) k.add(Key.W);
        if (a.has(PlayerInput.A)) k.add(Key.A);
        if (a.has(PlayerInput.S)) k.add(Key.S);
        if (a.has(PlayerInput.D)) k.add(Key.D);
        if (a.has(PlayerInput.JUMP)) k.add(Key.JUMP);
        if (a.has(PlayerInput.SPRINT)) k.add(Key.SPRINT);
        if (a.has(PlayerInput.SNEAK)) k.add(Key.SNEAK);
        if (a.has(PlayerInput.USE)) k.add(Key.USE);
        if (a.has(PlayerInput.CLOSE)) k.add(Key.CLOSE);
        return new TickInput(k, a.yaw, a.pitch, a.slot);
    }

    private static List<Path> findSeeds(Problem p, Path pf, List<Path> seedPaths) throws IOException {
        List<Path> out = new ArrayList<Path>();
        String stem = p.id.replace('/', '_');
        String base = pf.getFileName().toString().replace(".problem.json", "");
        for (Path s : seedPaths) {
            if (!Files.isDirectory(s)) {
                out.add(s);
                continue;
            }
            for (Path f : list(s, "*.solution.json")) {
                if (SolutionIO.readProblemId(read(f)).equals(p.id)) out.add(f);
            }
            for (String name : new String[] {stem + ".tas", base + ".tas"}) {
                Path f = s.resolve(name);
                if (Files.exists(f) && !out.contains(f)) out.add(f);
            }
        }
        if (p.target != null && p.target.witness != null) {
            Path w = pf.resolveSibling(p.target.witness);
            if (Files.exists(w)) out.add(w);
        }
        return out;
    }

    private static List<TickInput> readInputs(Path f, Problem p) throws IOException {
        String text = read(f);
        if (f.toString().endsWith(".json")) return SolutionIO.readInputs(text);
        return InputText.parse(text, p.spawn);
    }

    private static List<Path> list(Path dir, String glob) throws IOException {
        List<Path> files = new ArrayList<Path>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, glob)) {
            for (Path x : ds) files.add(x);
        }
        Collections.sort(files);
        return files;
    }

    private static String read(Path f) throws IOException {
        return new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
    }
}
