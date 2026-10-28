package io.github.konqasasas.mctas.technique.cli;

import io.github.konqasasas.mctas.physics.Physics;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
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
            case "window":
                window(args);
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
                + "        (px, pz) across the heading, feet at or above minY; compares with the inputs' own crossing");
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
