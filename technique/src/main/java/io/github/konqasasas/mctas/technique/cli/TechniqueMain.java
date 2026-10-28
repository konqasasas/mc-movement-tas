package io.github.konqasasas.mctas.technique.cli;

import io.github.konqasasas.mctas.physics.Physics;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.input.TickInput;
import io.github.konqasasas.mctas.problem.io.InputText;
import io.github.konqasasas.mctas.problem.io.ProblemIO;
import io.github.konqasasas.mctas.problem.judge.PhysicsModel;
import io.github.konqasasas.mctas.technique.Exit;
import io.github.konqasasas.mctas.technique.SegmentSearch;
import io.github.konqasasas.mctas.technique.StepWindow;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public final class TechniqueMain {
    private TechniqueMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        switch (args[0]) {
            case "ladder":
                ladder(args);
                break;
            case "neo":
                neo(args);
                break;
            case "ceiling":
                ceiling(args);
                break;
            case "window":
                window(args);
                break;
            case "runup":
                runup(args);
                break;
            case "segment":
                segment(args);
                break;
            default:
                usage();
        }
    }

    private static void usage() {
        System.err.println("usage:\n"
                + "  mctas-technique segment <problem.json> <inputs.tas> <fromTick> <headingYaw> <px|goal> <pz> <minY> <ground 0|1>\n        [--width N] [--cap N] [--max ticks] [--offsets a,b,c] [--extra N] [--cells 0|1]\n"
                + "        replays the inputs to fromTick, then searches for the fastest way past the plane through\n"
                + "        (px, pz) across the heading, feet at or above minY; compares with the inputs' own crossing\n"
                + "  mctas-technique runup <problem.json> <tries> <out.tas> <headingYaw> [width]\n"
                + "        searches the run-up into the start box, then from the best <tries> entries to the goal\n"
                + "  mctas-technique ladder [width]\n"
                + "        compares holding W with the search for climbing a ladder onto a wall\n"
                + "  mctas-technique neo [width]\n"
                + "        the fastest neo around a wall end, landing 1 to 4 blocks past the wall\n"
                + "  mctas-technique ceiling [width]\n"
                + "        ticks to cover 20 blocks under ceilings 2, 2.5, 3 high and none: run, jump chain, search\n"
                + "  mctas-technique window\n"
                + "        prints the clean jump distances for climbing a step");
    }

    /** Prints the clean jump windows for step heights, ceilings, entry speeds and takeoffs as Markdown. */
    private static void window(String[] a) {
        double[] heights = {0.5, 1.0};
        String[] entries = {"run 3", "run 40", "landing 6"};
        System.out.println("| step | ceiling | entry | stride (next tick) | takeoff | clean front-to-face distances (landing ticks) |");
        System.out.println("| --- | --- | --- | --- | --- | --- |");
        for (double h : heights) {
            for (int ce : new int[] {-1, 2, 1, 0}) {
                for (String e : entries) {
                    String[] ep = e.split(" ");
                    int n = Integer.parseInt(ep[1]);
                    PlayerState s = ep[0].equals("run") ? StepWindow.runUp(n) : StepWindow.landing(n);
                    for (StepWindow.Takeoff to : StepWindow.Takeoff.values()) {
                        List<StepWindow.Jump> js = StepWindow.sweep(h, ce < 0 ? 0 : 2, Math.max(ce, 0), s, to, 4.0, 0.02);
                        System.out.printf("| %.1f | %s | %s | %.4f | %s | %s |%n", h,
                                ce < 0 ? "none" : "gap 2, ends " + ce + " before", e, StepWindow.stride(s), to, ranges(js));
                    }
                }
            }
        }
    }

    /** Prints ladder climbs (holding W against the search) as Markdown, and the searched inputs. */
    private static void ladder(String[] a) {
        SegmentSearch.Options o = new SegmentSearch.Options();
        o.maxTicks = 120;
        if (a.length > 1) o.width = Integer.parseInt(a[1]);
        System.out.println("| height | entry | hold W | search |");
        System.out.println("| --- | --- | --- | --- |");
        StringBuilder detail = new StringBuilder();
        for (int h : new int[] {2, 3, 4, 6, 8}) {
            for (boolean running : new boolean[] {false, true}) {
                io.github.konqasasas.mctas.technique.LadderBench.Row r =
                        io.github.konqasasas.mctas.technique.LadderBench.run(h, 0.5, running, o);
                System.out.printf("| %d | %s | %d | %d |%n", h, running ? "running, 0.5 before" : "standing, 0.5 before",
                        r.holdW, r.search);
                if (r.inputs != null) {
                    detail.append("height ").append(h).append(running ? " running" : " standing").append(":");
                    for (PlayerInput x : r.inputs) detail.append(' ').append(x.keyString()).append('@').append(x.yaw);
                    detail.append('\n');
                }
            }
        }
        System.out.print(detail);
    }

    /** Prints the fastest neo around a wall end for landing floors 1 to 4 blocks past the wall. */
    private static void neo(String[] a) {
        SegmentSearch.Options o = new SegmentSearch.Options();
        o.maxTicks = 60;
        if (a.length > 1) o.width = Integer.parseInt(a[1]);
        for (int gap = 1; gap <= 4; gap++) {
            SegmentSearch.Result r = io.github.konqasasas.mctas.technique.NeoBench.run(gap, o);
            System.out.println("landing " + gap + " past the wall: " + (r == null ? "none" : r.ticks() + " ticks"));
            if (r == null) continue;
            PlayerState q = io.github.konqasasas.mctas.technique.NeoBench.entry();
            SparseBlockWorld w = io.github.konqasasas.mctas.technique.NeoBench.world(gap);
            int k = 0;
            for (PlayerInput x : r.inputs) {
                q = Physics.tick(w, q, x);
                System.out.printf("    %3d %-14s pos %.4f %.4f %.4f%s%n", ++k, x, q.posX, q.posY, q.posZ, q.onGround ? " G" : "");
            }
        }
    }

    /** Prints the ticks to cover 20 blocks under ceilings of several heights: run, jump chain, search. */
    private static void ceiling(String[] a) {
        SegmentSearch.Options o = new SegmentSearch.Options();
        o.maxTicks = 100;
        o.offsets = new float[] {0};
        if (a.length > 1) o.width = Integer.parseInt(a[1]);
        double dist = 20;
        System.out.println("| ceiling | run | jump on every landing | search |");
        System.out.println("| --- | --- | --- | --- |");
        for (double gap : new double[] {2, 2.5, 3, 0}) {
            System.out.printf("| %s | %d | %d | %d |%n", gap == 0 ? "none" : "" + gap,
                    io.github.konqasasas.mctas.technique.CeilingBench.policy(gap, dist, false),
                    io.github.konqasasas.mctas.technique.CeilingBench.policy(gap, dist, true),
                    io.github.konqasasas.mctas.technique.CeilingBench.search(gap, dist, o));
        }
    }

    private static String ranges(List<StepWindow.Jump> js) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < js.size()) {
            if (!js.get(i).clean) {
                i++;
                continue;
            }
            int j = i;
            int lo = js.get(i).landTicks, hi = lo;
            while (j + 1 < js.size() && js.get(j + 1).clean) {
                j++;
                lo = Math.min(lo, js.get(j).landTicks);
                hi = Math.max(hi, js.get(j).landTicks);
            }
            if (sb.length() > 0) sb.append(", ");
            sb.append(String.format("%.2f-%.2f (%s)", js.get(i).distance, js.get(j).distance, lo == hi ? "" + lo : lo + "-" + hi));
            i = j + 1;
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    /**
     * Searches the run-up from the spawn for ways into the start box (from outside it), ranks the entry
     * states by how far along and how fast they are, then searches from the best few to the goal and
     * writes the fastest whole run.
     */
    private static void runup(String[] a) throws Exception {
        Problem p = ProblemIO.read(Paths.get(a[1]));
        int tries = Integer.parseInt(a[2]);
        String out = a[3];
        float heading = Float.parseFloat(a[4]);
        int width = a.length > 5 ? Integer.parseInt(a[5]) : 20000;
        PhysicsModel m = new PhysicsModel(p);
        PlayerState spawn = m.spawn(p);
        Exit entry = (before, after) -> p.start.contains(after.posX, after.posY, after.posZ)
                && !p.start.contains(before.posX, before.posY, before.posZ);
        SegmentSearch.Options o = new SegmentSearch.Options();
        o.width = width;
        o.maxTicks = 60;
        o.extraTicks = 25;
        o.results = 100000;
        o.voidY = p.voidY;
        List<SegmentSearch.Result> ins = new SegmentSearch().search(p.world, spawn, Integer.MIN_VALUE / 2, entry,
                heading, o);
        double r = Math.toRadians(heading);
        double dx = -Math.sin(r), dz = Math.cos(r);
        java.util.function.ToDoubleFunction<SegmentSearch.Result> lead =
                q -> q.end.posX * dx + q.end.posZ * dz + 3 * (q.end.motionX * dx + q.end.motionZ * dz);
        ins.sort((x, y) -> Double.compare(lead.applyAsDouble(y), lead.applyAsDouble(x)));
        System.out.println(ins.size() + " ways into the start");
        Exit goal = Exit.box(p.goal.minX, p.goal.minY, p.goal.minZ, p.goal.maxX, p.goal.maxY, p.goal.maxZ);
        List<TickInput> best = null;
        int bestTicks = Integer.MAX_VALUE;
        for (int k = 0; k < Math.min(tries, ins.size()); k++) {
            SegmentSearch.Result e = ins.get(k);
            System.out.printf("entry %d: after %d ticks, %s%n", k, e.ticks(), e.end);
            SegmentSearch.Options g = new SegmentSearch.Options();
            g.width = width;
            g.maxTicks = Math.min(p.maxTicks, bestTicks == Integer.MAX_VALUE ? 300 : bestTicks);
            g.voidY = p.voidY;
            List<SegmentSearch.Result> rest = new SegmentSearch().search(p.world, e.end, e.lastToggle - e.ticks(), goal,
                    io.github.konqasasas.mctas.technique.Technique.headingTo(e.end, (p.goal.minX + p.goal.maxX) / 2,
                            (p.goal.minZ + p.goal.maxZ) / 2), g);
            if (rest.isEmpty()) continue;
            List<TickInput> all = new ArrayList<TickInput>();
            for (PlayerInput x : e.inputs) all.add(toTick(x));
            for (PlayerInput x : rest.get(0).inputs) all.add(toTick(x));
            io.github.konqasasas.mctas.problem.judge.RunResult rr = io.github.konqasasas.mctas.problem.judge.Evaluator.run(p, all);
            List<Integer> viol = io.github.konqasasas.mctas.problem.judge.ToggleRule.violations(p, all);
            System.out.println("  -> " + rr.status + " " + rr.ticks() + " ticks, toggle violations " + viol);
            if (rr.finished() && viol.isEmpty() && rr.ticks() < bestTicks) {
                bestTicks = rr.ticks();
                best = all;
            }
        }
        if (best != null) {
            Files.write(Paths.get(out), InputText.format(best, p.spawn).getBytes(StandardCharsets.UTF_8));
            System.out.println("wrote " + bestTicks + " ticks to " + out);
        }
    }

    /**
     * Writes the inputs up to {@code from}, then the segment, then (unless the segment already ends at
     * the goal) the fastest search from its exit to the goal, and judges the whole run.
     */
    private static void splice(Problem p, List<TickInput> in, int from, SegmentSearch.Result r,
                               boolean atGoal, SegmentSearch.Options o, String out) throws Exception {
        List<TickInput> all = new ArrayList<TickInput>(in.subList(0, from));
        for (PlayerInput x : r.inputs) all.add(toTick(x));
        if (!atGoal) {
            Exit goal = Exit.box(p.goal.minX, p.goal.minY, p.goal.minZ, p.goal.maxX, p.goal.maxY, p.goal.maxZ);
            SegmentSearch.Options g = o;
            g.maxTicks = Math.max(g.maxTicks, 200);
            float heading = io.github.konqasasas.mctas.technique.Technique.headingTo(r.end,
                    (p.goal.minX + p.goal.maxX) / 2, (p.goal.minZ + p.goal.maxZ) / 2);
            List<SegmentSearch.Result> rest = new SegmentSearch().search(p.world, r.end, r.lastToggle - r.ticks(), goal, heading, g);
            if (rest.isEmpty()) {
                System.out.println("splice: no way from the segment's exit to the goal");
                return;
            }
            for (PlayerInput x : rest.get(0).inputs) all.add(toTick(x));
        }
        io.github.konqasasas.mctas.problem.judge.RunResult rr = io.github.konqasasas.mctas.problem.judge.Evaluator.run(p, all);
        System.out.println("splice: " + rr.status + " " + rr.ticks() + " ticks, toggle violations "
                + io.github.konqasasas.mctas.problem.judge.ToggleRule.violations(p, all));
        Files.write(Paths.get(out), InputText.format(all, p.spawn).getBytes(StandardCharsets.UTF_8));
    }

    private static TickInput toTick(PlayerInput a) {
        java.util.Set<io.github.konqasasas.mctas.problem.input.Key> k =
                java.util.EnumSet.noneOf(io.github.konqasasas.mctas.problem.input.Key.class);
        if (a.has(PlayerInput.W)) k.add(io.github.konqasasas.mctas.problem.input.Key.W);
        if (a.has(PlayerInput.A)) k.add(io.github.konqasasas.mctas.problem.input.Key.A);
        if (a.has(PlayerInput.S)) k.add(io.github.konqasasas.mctas.problem.input.Key.S);
        if (a.has(PlayerInput.D)) k.add(io.github.konqasasas.mctas.problem.input.Key.D);
        if (a.has(PlayerInput.JUMP)) k.add(io.github.konqasasas.mctas.problem.input.Key.JUMP);
        if (a.has(PlayerInput.SPRINT)) k.add(io.github.konqasasas.mctas.problem.input.Key.SPRINT);
        if (a.has(PlayerInput.SNEAK)) k.add(io.github.konqasasas.mctas.problem.input.Key.SNEAK);
        return new TickInput(k, a.yaw, a.pitch);
    }

    private static float[] floats(String csv) {
        String[] p = csv.split(",");
        float[] f = new float[p.length];
        for (int i = 0; i < p.length; i++) f[i] = Float.parseFloat(p[i]);
        return f;
    }

    private static void segment(String[] a) throws Exception {
        Problem p = ProblemIO.read(Paths.get(a[1]));
        List<TickInput> in = InputText.parse(new String(Files.readAllBytes(Paths.get(a[2])), StandardCharsets.UTF_8),
                p.spawn);
        int from = Integer.parseInt(a[3]);
        float heading = Float.parseFloat(a[4]);
        Exit exit = a[5].equals("goal") ? Exit.box(p.goal.minX, p.goal.minY, p.goal.minZ, p.goal.maxX, p.goal.maxY,
                p.goal.maxZ) : Exit.plane(Double.parseDouble(a[5]), Double.parseDouble(a[6]), heading, 0,
                Double.parseDouble(a[7]), a[8].equals("1"));
        SegmentSearch.Options o = new SegmentSearch.Options();
        for (int i = 9; i + 1 < a.length; i += 2) {
            switch (a[i]) {
                case "--width": o.width = Integer.parseInt(a[i + 1]); break;
                case "--max": o.maxTicks = Integer.parseInt(a[i + 1]); break;
                case "--cap": o.bucketCap = Integer.parseInt(a[i + 1]); break;
                case "--offsets": o.offsets = floats(a[i + 1]); break;
                case "--extra": o.extraTicks = Integer.parseInt(a[i + 1]); break;
                case "--cells": o.cells = a[i + 1].equals("1"); break;
                default: throw new IllegalArgumentException("unknown option " + a[i]);
            }
        }
        o.voidY = p.voidY;
        PhysicsModel m = new PhysicsModel(p);
        PlayerState s = m.spawn(p);
        int lastToggle = Integer.MIN_VALUE / 2;
        List<PlayerState> states = new ArrayList<PlayerState>();
        states.add(s);
        for (int i = 0; i < in.size(); i++) {
            PlayerState ns = m.tick(s, in.get(i));
            if (i < from && (ns.sprinting != s.sprinting || ns.prevSneak != s.prevSneak)) lastToggle = i + 1;
            s = ns;
            states.add(s);
        }
        int base = -1;
        for (int t = from + 1; t < states.size(); t++) {
            if (exit.reached(states.get(t - 1), states.get(t))) {
                base = t - from;
                break;
            }
        }
        PlayerState entry = states.get(from);
        System.out.println("entry " + entry);
        System.out.println("inputs reach the exit after " + base + " ticks"
                + (base > 0 ? ": " + states.get(from + base) : ""));
        long t0 = System.nanoTime();
        if (System.getenv("MCTAS_GUIDE") != null) {
            o.guide = new ArrayList<PlayerInput>();
            for (int i = from; i < in.size(); i++) o.guide.add(PhysicsModel.toPhysics(in.get(i)));
        }
        SegmentSearch ss = new SegmentSearch();
        List<SegmentSearch.Result> rs = ss.search(p.world, entry, lastToggle - from, exit, heading, o);
        System.out.printf("search: %d results, %d ticks expanded, %.1f s%n", rs.size(), ss.expanded,
                (System.nanoTime() - t0) / 1e9);
        if (o.guide != null) System.out.println("guide lost at tick " + ss.guideLost);
        String splice = System.getenv("MCTAS_SPLICE");
        if (splice != null && !rs.isEmpty()) splice(p, in, from, rs.get(0), a[5].equals("goal"), o, splice);
        for (int k = 0; k < Math.min(5, rs.size()); k++) {
            SegmentSearch.Result r = rs.get(k);
            System.out.printf("  %d ticks, speed %.4f, end %s%n", r.ticks(), r.speedAlong(heading), r.end);
        }
        if (!rs.isEmpty()) {
            PlayerState q = entry;
            int k = 0;
            for (PlayerInput x : rs.get(0).inputs) {
                q = Physics.tick(p.world, q, x);
                System.out.printf("    %3d %-14s pos %.4f %.4f %.4f  v %.4f %.4f %.4f %s%s%n", ++k, x, q.posX, q.posY, q.posZ,
                        q.motionX, q.motionY, q.motionZ, q.onGround ? "G" : "-", q.collidedHorizontally ? " wall" : "");
            }
        }
    }
}
