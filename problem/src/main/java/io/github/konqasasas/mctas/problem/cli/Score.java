package io.github.konqasasas.mctas.problem.cli;

import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.Target;
import io.github.konqasasas.mctas.problem.input.TickInput;
import io.github.konqasasas.mctas.problem.io.ProblemIO;
import io.github.konqasasas.mctas.problem.io.SearchInfo;
import io.github.konqasasas.mctas.problem.io.SolutionIO;
import io.github.konqasasas.mctas.problem.judge.Evaluator;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code mctas-problem score}: the table that compares search engines on a problem set.
 *
 * <p>Each solution directory holds {@code *.solution.json} files from one engine, matched to the
 * problems by the {@code problem} id inside. Every solution is timed again with the physics here, so
 * the table never trusts the ticks an engine wrote. A cell shows the ticks, the difference to the
 * problem's target, the search time from {@code result.search.wallMillis}, and {@code !} when the
 * inputs break the sprint/sneak toggle rule ({@link ToggleRule}).
 */
final class Score {
    private Score() {
    }

    private static final class Cell {
        Integer ticks;
        long millis = -1;
        boolean toggleViolation;
        String status = "-";
    }

    static int run(String[] args) throws IOException {
        Path problemDir = Paths.get(args[1]);
        boolean markdown = false;
        List<Path> solutionDirs = new ArrayList<Path>();
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--markdown")) markdown = true;
            else solutionDirs.add(Paths.get(args[i]));
        }
        List<Path> problems = list(problemDir, "*.problem.json");
        List<String> engines = new ArrayList<String>();
        List<Map<String, Path>> byId = new ArrayList<Map<String, Path>>();
        for (Path d : solutionDirs) {
            Map<String, Path> m = new HashMap<String, Path>();
            String engine = null;
            for (Path f : list(d, "*.solution.json")) {
                String json = read(f);
                m.put(SolutionIO.readProblemId(json), f);
                SearchInfo si = SolutionIO.readSearch(json);
                if (engine == null && si != null) engine = si.engine;
            }
            byId.add(m);
            engines.add(engine != null ? engine : d.getFileName().toString());
        }

        List<String> header = new ArrayList<String>();
        header.add("problem");
        header.add("target");
        for (String e : engines) header.add(e);
        List<List<String>> rows = new ArrayList<List<String>>();
        int[] solved = new int[engines.size()];
        int[] atTarget = new int[engines.size()];
        long[] millis = new long[engines.size()];
        int[] tickSum = new int[engines.size()];
        int commonCount = 0;
        int failures = 0;
        for (Path pf : problems) {
            Problem problem = ProblemIO.read(pf);
            List<String> row = new ArrayList<String>();
            row.add(problem.id);
            Target t = problem.target;
            row.add(t == null ? "-" : t.ticks + (t.kind == Target.Kind.OPTIMAL ? " opt" : t.kind == Target.Kind.HUMAN_BENCHMARK ? " human" : " ub"));
            List<Cell> cells = new ArrayList<Cell>();
            for (Map<String, Path> m : byId) {
                Cell c = new Cell();
                Path f = m.get(problem.id);
                if (f != null) {
                    String json = read(f);
                    List<TickInput> inputs = SolutionIO.readInputs(json);
                    RunResult r = Evaluator.run(problem, inputs);
                    SearchInfo si = SolutionIO.readSearch(json);
                    if (si != null) c.millis = si.wallMillis;
                    c.status = r.status.name().toLowerCase();
                    if (r.finished()) {
                        c.ticks = r.ticks();
                        c.toggleViolation = !ToggleRule.violations(problem, inputs).isEmpty();
                        if (t != null && t.kind == Target.Kind.OPTIMAL && c.ticks < t.ticks) failures++;
                    }
                }
                cells.add(c);
            }
            boolean all = true;
            for (int i = 0; i < cells.size(); i++) {
                Cell c = cells.get(i);
                if (c.ticks == null) {
                    all = false;
                    row.add(c.status);
                    continue;
                }
                solved[i]++;
                if (c.millis > 0) millis[i] += c.millis;
                String s = String.valueOf(c.ticks);
                if (t != null) {
                    int d = c.ticks - t.ticks;
                    if (d <= 0) atTarget[i]++;
                    s += d == 0 ? " (=)" : d > 0 ? " (+" + d + ")" : " (" + d + ")";
                    if (t.kind == Target.Kind.HUMAN_BENCHMARK) s += " " + percent(c.ticks, t.ticks);
                }
                if (c.millis >= 0) s += " " + seconds(c.millis);
                if (c.toggleViolation) s += " !";
                row.add(s);
            }
            if (all && !cells.isEmpty()) {
                commonCount++;
                for (int i = 0; i < cells.size(); i++) tickSum[i] += cells.get(i).ticks;
            }
            rows.add(row);
        }
        List<String> sum = new ArrayList<String>();
        sum.add("solved / at or under target");
        sum.add(String.valueOf(problems.size()));
        for (int i = 0; i < engines.size(); i++) sum.add(solved[i] + " / " + atTarget[i]);
        rows.add(sum);
        List<String> total = new ArrayList<String>();
        total.add("ticks on the " + commonCount + " solved by all, search time");
        total.add("");
        for (int i = 0; i < engines.size(); i++) total.add(tickSum[i] + " " + seconds(millis[i]));
        rows.add(total);
        print(header, rows, markdown);
        System.out.println("(=) at target, (+n) slower, (-n) faster than the target; ! breaks the toggle rule (README)");
        System.out.println("human targets: a top human time, not a bound; n% is the solution's ticks over it (100% ties it)");
        if (failures > 0) {
            System.out.println("ERROR: " + failures + " solution(s) beat a target marked optimal");
            return 1;
        }
        return 0;
    }

    private static String percent(int ticks, int target) {
        return Math.round(100.0 * ticks / target) + "%";
    }

    private static String seconds(long millis) {
        return millis < 0 ? "" : String.format("%.1fs", millis / 1000.0);
    }

    private static void print(List<String> header, List<List<String>> rows, boolean markdown) {
        List<List<String>> all = new ArrayList<List<String>>();
        all.add(header);
        all.addAll(rows);
        if (markdown) {
            for (int r = 0; r < all.size(); r++) {
                System.out.println("| " + String.join(" | ", all.get(r)) + " |");
                if (r == 0) {
                    StringBuilder sb = new StringBuilder("|");
                    for (int i = 0; i < header.size(); i++) sb.append(" --- |");
                    System.out.println(sb);
                }
            }
            return;
        }
        int[] w = new int[header.size()];
        for (List<String> row : all) {
            for (int i = 0; i < row.size(); i++) w[i] = Math.max(w[i], row.get(i).length());
        }
        for (List<String> row : all) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < row.size(); i++) {
                String c = row.get(i);
                sb.append(c);
                for (int k = c.length(); k < w[i] + 2; k++) sb.append(' ');
            }
            System.out.println(sb.toString().trim());
        }
    }

    private static List<Path> list(Path dir, String glob) throws IOException {
        List<Path> files = new ArrayList<Path>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, glob)) {
            for (Path p : ds) files.add(p);
        }
        Collections.sort(files);
        return files;
    }

    private static String read(Path f) throws IOException {
        return new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
    }
}
