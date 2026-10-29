package io.github.konqasasas.mctas.planner;

import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.problem.Region;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * The middle level: A* over landings. A node is a run that just landed on a foothold (or a root at the
 * start entry); its key rounds the position and the horizontal velocity, so that the search keeps one
 * run per speed state on each foothold. Edges come from the {@link Maneuver}s; the cost is ticks on
 * the judge's clock; the heuristic is {@link Course#toGo}, a lower bound at the best speed.
 */
final class Planner {
    static final class Node {
        final Run run;
        final Node parent;
        final Move move;
        final List<PlayerInput> inputs;
        final int g;
        final double f;

        Node(Run run, Node parent, Move move, List<PlayerInput> inputs, double f) {
            this.run = run;
            this.parent = parent;
            this.move = move;
            this.inputs = inputs;
            this.g = run.clock();
            this.f = f;
        }
    }

    /** A plan: the root's run-up inputs, then the moves, and the time it takes. */
    static final class Plan {
        final Runup.Root root;
        final List<Move> moves;
        final List<PlayerInput> inputs;
        final int time;
        /** The platforms the plan lands on (from the search; empty for a plan built elsewhere). */
        final java.util.Set<Integer> platforms = new java.util.HashSet<Integer>();

        Plan(Runup.Root root, List<Move> moves, List<PlayerInput> inputs, int time) {
            this.root = root;
            this.moves = moves;
            this.inputs = inputs;
            this.time = time;
        }
    }

    /** Heuristic weight: above 1 finds a first plan sooner, at the price of optimality. */
    double weight = 1.5;
    /** Keep a landing only if it is at most this many ticks behind the best landing on that surface. */
    int window = 4;
    /** Footholds to aim for from a node. */
    int targets = 4;
    double posCell = 0.2, velCell = 0.04;
    /** Successors may land at most this many ticks further from the goal (by the bound) than their node. */
    double backSlack = 8;
    /** Jump branching reaches this far past the best jump's reach (see {@link StepManeuver}). */
    double far = 1.5;
    /** Share of the time left that the round keeps after its first plan (the rest goes to the rounds after it). */
    double afterFirst = 1.0;
    /** Also aim along the racing line through each foothold. */
    boolean through = true;
    /** When set, aim only for footholds on these platforms (the platforms of the plans found so far). */
    java.util.Set<Integer> corridor;

    private final Course course;
    private final List<Maneuver> maneuvers = new ArrayList<Maneuver>();
    /** Time spent in each maneuver (with {@link #DEBUG}). */
    final Map<String, Long> maneuverNanos = new java.util.TreeMap<String, Long>();
    int expanded, generated;
    static final boolean DEBUG = System.getenv("PLANNER_DEBUG") != null;
    private double closest = Double.POSITIVE_INFINITY;

    Planner(Course course) {
        this.course = course;
        maneuvers.add(new ClimbManeuver());
        maneuvers.add(new HoleManeuver());
        maneuvers.add(new BounceClimbManeuver());
    }

    Planner add(Maneuver m) {
        maneuvers.add(m);
        return this;
    }

    Plan search(List<Runup.Root> roots, long deadline, int bound) {
        StepManeuver steps = new StepManeuver(far);
        PriorityQueue<Node> open = new PriorityQueue<Node>(64, (a, b) -> a.f != b.f ? Double.compare(a.f, b.f) : Integer.compare(b.g, a.g));
        Map<Long, Integer> seen = new HashMap<Long, Integer>();
        Map<Integer, Integer> bestOnSurface = new HashMap<Integer, Integer>();
        Map<Node, Runup.Root> rootOf = new HashMap<Node, Runup.Root>();
        for (Runup.Root r : roots) {
            Node n = new Node(r.run, null, null, r.inputs, weight * h(r.run));
            if (admit(n, seen, bestOnSurface)) {
                open.add(n);
                rootOf.put(n, r);
            }
        }
        Node best = null;
        int bestTime = bound;
        List<Maneuver.Successor> succ = new ArrayList<Maneuver.Successor>();
        while (!open.isEmpty() && System.currentTimeMillis() < deadline) {
            Node n = open.poll();
            double hn = h(n.run);
            if (n.g + hn >= bestTime) continue;
            boolean trace = false;
            if (DEBUG && hn < closest) {
                closest = hn;
                trace = true;
                System.err.printf("  closest h %.1f g %d at (%.2f, %.2f, %.2f) v (%.3f, %.3f) ground %b, %d expanded%n", hn, n.g,
                    n.run.s.posX, n.run.s.posY, n.run.s.posZ, n.run.s.motionX, n.run.s.motionZ, n.run.s.onGround, expanded);
            }
            Integer s = seen.get(key(n.run));
            if (s != null && s < n.g) continue;
            expanded++;
            succ.clear();
            List<double[]> aims = aims(n.run);
            if (trace) for (double[] a : aims) System.err.printf("      aim (%.2f, %.2f) top %.2f%s%n", a[0], a[1], a[2], a.length == 8 ? " corner" : a.length >= 14 ? " bounce" : a.length >= 9 ? String.format(" via (%.2f, %.2f)", a[7], a[8]) : "");
            expandParallel(steps, n.run, aims, succ);
            for (Maneuver m : maneuvers) {
                long t0 = DEBUG ? System.nanoTime() : 0;
                m.expand(n.run, aims, succ);
                if (DEBUG) maneuverNanos.merge(m.getClass().getSimpleName(), System.nanoTime() - t0, Long::sum);
            }
            int[] why = new int[4];
            for (Maneuver.Successor x : succ) {
                generated++;
                if (x.run.done()) {
                    int t = x.run.time();
                    if (t < bestTime) {
                        if (best == null) {
                            long now = System.currentTimeMillis();
                            deadline = Math.min(deadline, now + (long) ((deadline - now) * afterFirst));
                        }
                        bestTime = t;
                        best = new Node(x.run, n, x.move, x.inputs, t);
                        if (DEBUG) System.err.printf("  found %d ticks, %d expanded, %d ms before the deadline%n", t, expanded, deadline - System.currentTimeMillis());
                    }
                    continue;
                }
                Node c = new Node(x.run, n, x.move, x.inputs, 0);
                double hc = h(x.run);
                // A landing much further from the goal than its parent (a fall back down) is dropped.
                if (hc > hn + backSlack) {
                    why[0]++;
                    continue;
                }
                if (c.g + hc >= bestTime) {
                    why[1]++;
                    continue;
                }
                c = new Node(x.run, n, x.move, x.inputs, c.g + weight * hc);
                if (admit(c, seen, bestOnSurface)) {
                    open.add(c);
                    why[3]++;
                } else {
                    why[2]++;
                    if (trace && why[2] <= 3) {
                        Course.Surface su = x.run.s.onGround ? course.support(x.run.s.posX, x.run.s.posY, x.run.s.posZ) : null;
                        System.err.printf("      rejected g %d at (%.2f, %.2f, %.2f) surface %s best %s seen %s%n", c.g, x.run.s.posX, x.run.s.posY, x.run.s.posZ,
                            su, su == null ? "-" : bestOnSurface.get(su.id), seen.get(key(x.run)));
                    }
                }
            }
            if (trace) System.err.printf("    %d successors: %d fall back, %d over the bound, %d seen or out of the window, %d open (open list %d)%n",
                succ.size(), why[0], why[1], why[2], why[3], open.size());
        }
        if (best == null) return null;
        List<Node> chain = new ArrayList<Node>();
        for (Node k = best; k != null; k = k.parent) chain.add(k);
        Collections.reverse(chain);
        Node first = chain.get(0);
        Runup.Root root = null;
        for (Map.Entry<Node, Runup.Root> e : rootOf.entrySet()) if (e.getKey() == first) root = e.getValue();
        List<Move> moves = new ArrayList<Move>();
        List<PlayerInput> inputs = new ArrayList<PlayerInput>(root.inputs);
        for (int i = 1; i < chain.size(); i++) {
            moves.add(chain.get(i).move);
            inputs.addAll(chain.get(i).inputs);
        }
        Plan plan = new Plan(root, moves, inputs, bestTime);
        for (Node k : chain) {
            int pl = course.platformAt(k.run.s);
            if (pl >= 0) plan.platforms.add(pl);
        }
        return plan;
    }

    /**
     * The roots worth searching from: each is scored by its best one-step continuation towards its two
     * most promising aims (clock plus lower bound after it), and the best {@code keep} distinct ones win.
     */
    List<Runup.Root> pickRoots(List<Runup.Root> roots, int keep) {
        final Map<Runup.Root, Double> score = new HashMap<Runup.Root, Double>();
        StepManeuver probe = new StepManeuver(far);
        List<Maneuver.Successor> succ = new ArrayList<Maneuver.Successor>();
        for (Runup.Root r : roots) {
            List<double[]> aims = aims(r.run);
            if (aims.size() > 2) aims = aims.subList(0, 2);
            succ.clear();
            probe.expand(r.run, aims, succ);
            double best = Double.POSITIVE_INFINITY;
            for (Maneuver.Successor x : succ) {
                best = Math.min(best, x.run.done() ? x.run.time() : x.run.clock() + h(x.run));
            }
            score.put(r, best);
        }
        List<Runup.Root> sorted = new ArrayList<Runup.Root>(roots);
        sorted.sort((p, q) -> Double.compare(score.get(p), score.get(q)));
        List<Runup.Root> out = new ArrayList<Runup.Root>();
        java.util.Set<Long> keys = new java.util.HashSet<Long>();
        for (Runup.Root r : sorted) {
            if (out.size() >= keep || score.get(r) == Double.POSITIVE_INFINITY) break;
            if (keys.add(key(r.run))) {
                out.add(r);
                if (DEBUG) System.err.printf("  root %s: score %.1f at (%.2f, %.2f, %.2f) v (%.3f, %.3f) tick %d%n", r.label, score.get(r),
                    r.run.s.posX, r.run.s.posY, r.run.s.posZ, r.run.s.motionX, r.run.s.motionZ, r.run.tick);
            }
        }
        return out;
    }

    /** Worker threads for expanding a node's aims at once (the physics and the course are read-only). */
    static final int THREADS = Integer.getInteger("planner.threads", Runtime.getRuntime().availableProcessors());
    private static final java.util.concurrent.ForkJoinPool POOL = new java.util.concurrent.ForkJoinPool(Math.max(1, THREADS));

    /** {@code m.expand} over the aims, one aim per task, successors in aim order. */
    private static void expandParallel(final Maneuver m, final Run from, List<double[]> aims, List<Maneuver.Successor> out) {
        if (THREADS <= 1 || aims.size() <= 1) {
            m.expand(from, aims, out);
            return;
        }
        List<java.util.concurrent.Future<List<Maneuver.Successor>>> fs = new ArrayList<java.util.concurrent.Future<List<Maneuver.Successor>>>();
        for (final double[] aim : aims) {
            fs.add(POOL.submit(() -> {
                List<Maneuver.Successor> part = new ArrayList<Maneuver.Successor>();
                m.expand(from, Collections.singletonList(aim), part);
                return part;
            }));
        }
        try {
            for (java.util.concurrent.Future<List<Maneuver.Successor>> f : fs) out.addAll(f.get());
        } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException(e);
        }
    }

    private double h(Run r) {
        return course.toGo(r.s.posX, r.s.posY, r.s.posZ);
    }

    private boolean admit(Node n, Map<Long, Integer> seen, Map<Integer, Integer> bestOnSurface) {
        long k = key(n.run);
        Integer s = seen.get(k);
        if (s != null && s <= n.g) return false;
        if (n.run.s.onGround) {
            Course.Surface c = course.support(n.run.s.posX, n.run.s.posY, n.run.s.posZ);
            if (c != null) {
                Integer b = bestOnSurface.get(c.id);
                if (b != null && n.g > b + window) return false;
                if (b == null || n.g < b) bestOnSurface.put(c.id, n.g);
            }
        }
        seen.put(k, n.g);
        return true;
    }

    private long key(Run r) {
        long h = 1469598103934665603L;
        h = mix(h, Math.round(r.s.posX / posCell));
        h = mix(h, Math.round(r.s.posY / 0.01));
        h = mix(h, Math.round(r.s.posZ / posCell));
        h = mix(h, Math.round(r.s.motionX / velCell));
        h = mix(h, Math.round(r.s.motionZ / velCell));
        h = mix(h, (r.s.onGround ? 1 : 0) | (r.s.sprinting ? 2 : 0) | (r.inStart ? 4 : 0));
        return h;
    }

    private static long mix(long h, long v) {
        h ^= v;
        h *= 1099511628211L;
        return h ^ (h >>> 29);
    }

    /**
     * Points to head for next: on the footholds nearby that are not on the player's platform, ranked by
     * the distance there plus their lower bound to the goal; the nearest point (a little inside) and the
     * centre of each, at most two footholds per platform. The goal box too when it is near.
     */
    List<double[]> aims(Run r) {
        double x = r.s.posX, y = r.s.posY, z = r.s.posZ;
        int here = course.platformAt(r.s);
        double v = course.reach.maxSpeed();
        List<Course.Surface> cand = new ArrayList<Course.Surface>();
        List<double[]> out = new ArrayList<double[]>();
        boolean goalNear = false;
        if (here >= 0) {
            for (Course.Surface a : course.platforms.get(here)) {
                goalNear |= a.nearGoal;
                for (Course.Surface b : a.next) {
                    if (b.platform != here && b.toGo < Double.POSITIVE_INFINITY && !a.notByStep.contains(b) && !cand.contains(b)) cand.add(b);
                }
            }
        } else {
            int rad = (int) Math.ceil(course.reach.reach(-8) + Course.REACH_MARGIN) + 1;
            for (int ix = -rad; ix <= rad; ix++) {
                for (int iz = -rad; iz <= rad; iz++) {
                    List<Course.Surface> list = course.column((int) Math.floor(x) + ix, (int) Math.floor(z) + iz);
                    if (list == null) continue;
                    for (Course.Surface b : list) {
                        if (b.toGo == Double.POSITIVE_INFINITY || b.top > y + course.reach.maxRise()) continue;
                        if (b.dist(x, z) > course.reach.reach(b.top - y) + Course.REACH_MARGIN) continue;
                        cand.add(b);
                        goalNear |= b.nearGoal;
                    }
                }
            }
        }
        cand.sort((p, q) -> Double.compare(Math.hypot(p.cx() - x, p.cz() - z) / v + p.toGo, Math.hypot(q.cx() - x, q.cz() - z) / v + q.toGo));
        if (here >= 0 && cand.size() > targets) {
            // Keep the straight-line order, but make room among the targets for the best candidate by
            // the walk over this platform: the nearest ones may all be behind a wall (a slab up out of
            // a slalom of panes, F-523).
            Map<Course.Surface, Double> walk = walkFrom(course.support(x, y, z));
            Course.Surface pick = null;
            double ps = Double.POSITIVE_INFINITY;
            for (Course.Surface b : cand) {
                for (Map.Entry<Course.Surface, Double> e : walk.entrySet()) {
                    Course.Surface a = e.getKey();
                    if (!a.next.contains(b) || a.notByStep.contains(b)) continue;
                    double sc = (e.getValue() + Math.hypot(b.cx() - a.cx(), b.cz() - a.cz())) / v + b.toGo;
                    if (sc < ps) {
                        ps = sc;
                        pick = b;
                    }
                }
            }
            int i = pick == null ? -1 : cand.indexOf(pick);
            if (i >= targets - 1) {
                cand.remove(i);
                cand.add(targets - 1, pick);
            }
        }
        Map<Integer, Integer> perPlatform = new HashMap<Integer, Integer>();
        int taken = 0;
        for (Course.Surface b : cand) {
            if (taken >= targets) break;
            if (corridor != null && !corridor.contains(b.platform)) continue;
            int k = perPlatform.getOrDefault(b.platform, 0);
            if (k >= 2) continue;
            perPlatform.put(b.platform, k + 1);
            taken++;
            double bx0 = b.minX + 0.3, bx1 = b.maxX - 0.3, bz0 = b.minZ + 0.3, bz1 = b.maxZ - 0.3;
            double ix = Math.min(0.25, (bx1 - bx0) / 2), iz = Math.min(0.25, (bz1 - bz0) / 2);
            double nx = clamp(x, bx0 + ix, bx1 - ix), nz = clamp(z, bz0 + iz, bz1 - iz);
            out.add(new double[] {nx, nz, b.top, b.minX, b.maxX, b.minZ, b.maxZ});
            addVias(x, z, Math.max(y, b.top), new double[] {nx, nz, b.top, b.minX, b.maxX, b.minZ, b.maxZ}, out);
            if (here >= 0) addCorner(r, new double[] {nx, nz, b.top, b.minX, b.maxX, b.minZ, b.maxZ}, a -> a.next.contains(b) && !a.notByStep.contains(b), out);
            // A bounce: by way of the slime block's middle.
            if (here >= 0) {
                for (Course.Surface a : course.platforms.get(here)) {
                    Course.Surface sl = a.bounceVia.get(b);
                    if (sl == null) continue;
                    // Land with the centre well over the slime, at its point nearest the foothold.
                    double[] e = course.slimeExtent(sl);
                    double in = BounceClimbManeuver.LAND_IN;
                    double sx0 = e[0] + in, sx1 = e[1] - in, sz0 = e[2] + in, sz1 = e[3] - in;
                    double sx = clamp(nx, Math.min(sx0 + 0.1, (sx0 + sx1) / 2), Math.max(sx1 - 0.1, (sx0 + sx1) / 2));
                    double sz = clamp(nz, Math.min(sz0 + 0.1, (sz0 + sz1) / 2), Math.max(sz1 - 0.1, (sz0 + sz1) / 2));
                    out.add(new double[] {nx, nz, b.top, b.minX, b.maxX, b.minZ, b.maxZ, sx, sz, sx0, sx1, sz0, sz1, sl.top});
                    break;
                }
            }
            // The racing line: the point of b nearest to the straight line from here towards b's best next foothold.
            Course.Surface after = bestNext(b);
            if (after != null && through) {
                double[] q = closestOnSegment(x, z, after.cx(), after.cz(), bx0 + ix, bx1 - ix, bz0 + iz, bz1 - iz);
                if (Math.hypot(q[0] - nx, q[1] - nz) > 0.15) out.add(new double[] {q[0], q[1], b.top, b.minX, b.maxX, b.minZ, b.maxZ});
            }
        }
        // The bodies off this platform with the best footholds beyond them.
        if (here >= 0) {
            List<Integer> bodies = new ArrayList<Integer>();
            List<Double> best = new ArrayList<Double>();
            for (Course.Surface a : course.platforms.get(here)) {
                for (int i = 0; i < a.viaBody.size(); i++) {
                    int b = a.viaBodyId.get(i);
                    double t = a.viaBody.get(i).toGo;
                    int k = bodies.indexOf(b);
                    if (k < 0) {
                        bodies.add(b);
                        best.add(t);
                    } else if (t < best.get(k)) best.set(k, t);
                }
            }
            Integer[] order = new Integer[bodies.size()];
            for (int i = 0; i < order.length; i++) order[i] = i;
            java.util.Arrays.sort(order, (p, q) -> Double.compare(best.get(p), best.get(q)));
            for (int i = 0; i < Math.min(BODY_CORNERS, order.length); i++) addBodyCorner(r, bodies.get(order[i]), out);
        }
        if (goalNear || here < 0) {
            Region g = course.problem.goal;
            double ix = Math.min(0.2, (g.maxX - g.minX) / 2), iz = Math.min(0.2, (g.maxZ - g.minZ) / 2);
            double[] ga = {clamp(x, g.minX + ix, g.maxX - ix), clamp(z, g.minZ + iz, g.maxZ - iz), g.minY, g.minX, g.maxX, g.minZ, g.maxZ};
            out.add(ga);
            if (here >= 0) addCorner(r, ga, a -> a.nearGoal, out);
        }
        return out;
    }

    private final Map<Course.Surface, Map<Course.Surface, Double>> walks = new java.util.concurrent.ConcurrentHashMap<Course.Surface, Map<Course.Surface, Double>>();

    /**
     * Walking distances over the platform from a foothold (Dijkstra over neighbouring footholds of the
     * same height with a clear line between their middles; the first step from the start is not
     * checked, since the start's middle may be inside a wall the player stands beside).
     */
    private Map<Course.Surface, Double> walkFrom(Course.Surface start) {
        if (start == null) return Collections.emptyMap();
        return walks.computeIfAbsent(start, st -> {
            Map<Course.Surface, Double> d = new HashMap<Course.Surface, Double>();
            PriorityQueue<Object[]> q = new PriorityQueue<Object[]>((p, o) -> Double.compare((Double) p[1], (Double) o[1]));
            d.put(st, 0.0);
            q.add(new Object[] {st, 0.0});
            while (!q.isEmpty() && d.size() < CORNER_SEARCH) {
                Object[] e = q.poll();
                Course.Surface a = (Course.Surface) e[0];
                double da = (Double) e[1];
                if (da > d.get(a)) continue;
                for (int[] dd : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    List<Course.Surface> col = course.column(a.x + dd[0], a.z + dd[1]);
                    if (col == null) continue;
                    for (Course.Surface n : col) {
                        if (n.platform != a.platform || n.top != a.top) continue;
                        if (a != st && course.blockedLine(a.cx(), a.cz(), n.cx(), n.cz(), a.top)) continue;
                        double dn = da + Math.hypot(n.cx() - a.cx(), n.cz() - a.cz());
                        Double old = d.get(n);
                        if (old != null && old <= dn) continue;
                        d.put(n, dn);
                        q.add(new Object[] {n, dn});
                    }
                }
            }
            return d;
        });
    }

    /** Bodies off the platform that a corner towards an entry is looked for. */
    static final int BODY_CORNERS = 2;
    /** Footholds a corner search ({@link #addCorner}) visits at most. */
    static final int CORNER_SEARCH = 600;

    /**
     * When walls on the player's own platform stand between the player and an aim (a slalom between
     * posts or panes), the first corner to round, as an aim of its own: the shortest walk
     * over the platform's footholds to one from which the move is made ({@code from}) with a clear line
     * into the aim's region, pulled straight to its furthest foothold the player can see.
     */
    private void addCorner(Run r, double[] aim, java.util.function.Predicate<Course.Surface> from, List<double[]> out) {
        double x = r.s.posX, y = r.s.posY, z = r.s.posZ;
        if (!course.blockedLine(x, z, aim[0], aim[1], y)) return;
        addCorner(r, a -> from.test(a) && !course.blockedLine(a.cx(), a.cz(), clamp(a.cx(), aim[3], aim[4]), clamp(a.cz(), aim[5], aim[6]), a.top), out);
    }

    /**
     * The same for a body of ladders, vines or water the platform leads into (handled by
     * {@link ClimbManeuver}, which runs straight at the entry): when no entry is in sight from where
     * the player stands, the corner on the way to a foothold that has one.
     */
    private void addBodyCorner(Run r, int body, List<double[]> out) {
        List<int[]> cells = course.bodies.get(body);
        java.util.function.Predicate<Course.Surface> sees = a -> {
            for (int[] c : cells) {
                if (course.canEnter(a, c) && !course.blockedLine(a.cx(), a.cz(), c[0] + 0.5, c[2] + 0.5, Math.min(a.top, c[1]))) return true;
            }
            return false;
        };
        Course.Surface start = course.support(r.s.posX, r.s.posY, r.s.posZ);
        if (start == null || sees.test(start)) return;
        addCorner(r, sees, out);
    }

    /** The corner on the walk over the player's platform to the nearest foothold that {@code goal} accepts. */
    private void addCorner(Run r, java.util.function.Predicate<Course.Surface> goal, List<double[]> out) {
        double x = r.s.posX, y = r.s.posY, z = r.s.posZ;
        Course.Surface start = course.support(x, y, z);
        if (start == null) return;
        Map<Course.Surface, Course.Surface> back = new HashMap<Course.Surface, Course.Surface>();
        java.util.ArrayDeque<Course.Surface> queue = new java.util.ArrayDeque<Course.Surface>();
        back.put(start, start);
        queue.add(start);
        Course.Surface end = null;
        while (!queue.isEmpty() && back.size() < CORNER_SEARCH) {
            Course.Surface a = queue.poll();
            if (a != start && goal.test(a)) {
                end = a;
                break;
            }
            for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                List<Course.Surface> col = course.column(a.x + d[0], a.z + d[1]);
                if (col == null) continue;
                for (Course.Surface n : col) {
                    if (n.platform != a.platform || n.top != a.top || back.containsKey(n)) continue;
                    if (course.blockedLine(a.cx(), a.cz(), n.cx(), n.cz(), a.top)) continue;
                    back.put(n, a);
                    queue.add(n);
                }
            }
        }
        if (end == null) return;
        List<Course.Surface> path = new ArrayList<Course.Surface>();
        for (Course.Surface a = end; a != start; a = back.get(a)) path.add(0, a);
        Course.Surface corner = null;
        for (Course.Surface a : path) {
            if (course.blockedLine(x, z, a.cx(), a.cz(), y)) break;
            corner = a;
        }
        if (corner == null) return;
        for (double[] o : out) if (o.length == 8 && o[0] == corner.cx() && o[1] == corner.cz()) return;
        // Length 8 marks a corner: the walk stops there (StepManeuver).
        out.add(new double[] {corner.cx(), corner.cz(), corner.top, corner.minX, corner.maxX, corner.minZ, corner.maxZ, 1});
    }

    /**
     * When a wall stands on the straight way to an aim, the same aim with a via point beside the wall:
     * the middle of the way pushed sideways, the nearest offset that clears both legs, on each side.
     */
    private void addVias(double x, double z, double base, double[] aim, List<double[]> out) {
        if (!course.blockedLine(x, z, aim[0], aim[1], base)) return;
        double dx = aim[0] - x, dz = aim[1] - z, len = Math.hypot(dx, dz);
        if (len < 1e-6) return;
        double nx = -dz / len, nz = dx / len;
        for (int side = -1; side <= 1; side += 2) {
            for (double f : new double[] {0.35, 0.5, 0.65}) {
                boolean found = false;
                for (double off = 0.6; off <= 2.41 && !found; off += 0.6) {
                    double vx = x + dx * f + nx * off * side, vz = z + dz * f + nz * off * side;
                    if (course.blockedLine(x, z, vx, vz, base) || course.blockedLine(vx, vz, aim[0], aim[1], base)) continue;
                    double[] a = java.util.Arrays.copyOf(aim, 9);
                    a[7] = vx;
                    a[8] = vz;
                    out.add(a);
                    found = true;
                }
                if (found) break;
            }
        }
    }

    /** The foothold after b on the way to the goal: the lowest bound among b's moves off its platform. */
    private Course.Surface bestNext(Course.Surface b) {
        Course.Surface best = null;
        for (Course.Surface n : b.next) {
            if (n.platform == b.platform) continue;
            if (best == null || n.toGo < best.toGo) best = n;
        }
        return best != null && best.toGo < b.toGo ? best : null;
    }

    /** The point of the rectangle nearest to the segment (x1,z1)-(x2,z2), by sampling the segment. */
    private static double[] closestOnSegment(double x1, double z1, double x2, double z2, double minX, double maxX, double minZ, double maxZ) {
        double best = Double.POSITIVE_INFINITY, bx = x1, bz = z1;
        for (int i = 0; i <= 32; i++) {
            double f = i / 32.0;
            double x = x1 + (x2 - x1) * f, z = z1 + (z2 - z1) * f;
            double cx = clamp(x, minX, maxX), cz = clamp(z, minZ, maxZ);
            double d = Math.hypot(cx - x, cz - z);
            if (d < best - 1e-12) {
                best = d;
                bx = cx;
                bz = cz;
            }
        }
        return new double[] {bx, bz};
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
