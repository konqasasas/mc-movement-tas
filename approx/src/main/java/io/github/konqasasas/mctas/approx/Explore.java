package io.github.konqasasas.mctas.approx;

import io.github.konqasasas.mctas.physics.Physics;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.Region;
import io.github.konqasasas.mctas.problem.judge.ToggleRule;
import io.github.konqasasas.mctas.technique.Exit;
import io.github.konqasasas.mctas.technique.SegmentSearch;
import io.github.konqasasas.mctas.technique.Target;
import io.github.konqasasas.mctas.technique.Technique;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Best-first search over the surfaces, each step a landing found by the segment search with the
 * real physics: from a node (standing on a surface), the nearby surfaces that are lower in the route
 * graph's cost to the goal ({@link Route#costToGoal}) are tried in that order, and every landing made
 * is a new node, ranked by ticks so far plus that cost. Unlike {@link Guide} it backs out of dead ends.
 * For courses whose route graph is only a guess (ladders, water, odd blocks).
 */
final class Explore {
    /** How far a landing may be, horizontally, and how high up without a ladder, vine or water near. */
    static final double REACH = 5.5, RISE = 1.3;
    /** How far a landing may be down a drop. */
    static final double FAR = 9;
    /** Surfaces tried from each node. */
    static final int FAN = 8;
    /** Of these, the first few also get the wide search when the quick one finds nothing. */
    static final int WIDE = 3;
    /** A landing must bring the cost down at least this much. */
    static final double MIN_GAIN = 2;
    /** Candidates at the same height tried from one node. */
    static final int PER_TOP = 3;
    /** Ticks a block of distance from the slime block counts against a landing after a bounce. */
    static final double BOUNCE_ASIDE = 10;
    /** Ways onto a slime block tried for one bounce. */
    static final int BOUNCES = 4;
    /** The quick search's budget (it stops on its own after one beam of 200). */
    static final long QUICK_MILLIS = 1500;

    private final Problem problem;
    private final Route route;
    private Bridge.PrintStreamLog log;

    private static final class Node {
        final PlayerState state;
        final List<PlayerInput> inputs;
        final int lastToggle;
        final int entry;
        final double f;
        final Route.Surface on;

        Node(PlayerState state, List<PlayerInput> inputs, int lastToggle, int entry, double f, Route.Surface on) {
            this.state = state;
            this.inputs = inputs;
            this.lastToggle = lastToggle;
            this.entry = entry;
            this.f = f;
            this.on = on;
        }
    }

    Explore(Problem problem, Route route) {
        this.problem = problem;
        this.route = route;
    }

    Explore log(Bridge.PrintStreamLog log) {
        this.log = log;
        return this;
    }

    /** Inputs from the spawn into the goal, or null when the deadline comes first or nothing is left. */
    List<PlayerInput> run(long deadlineMillis, long segmentMillis) {
        Region start = problem.start, goal = problem.goal;
        PlayerState s0 = Rollout.spawn(problem);
        boolean in0 = start.contains(s0.posX, s0.posY, s0.posZ);
        PriorityQueue<Node> open = new PriorityQueue<Node>(Comparator.comparingDouble(n -> n.f));
        if (System.getenv("EXPLORE_FROM") != null) {
            // Debug: from a standing state somewhere on the course (its inputs do not replay from the spawn).
            String[] xyz = System.getenv("EXPLORE_FROM").split(",");
            open.add(new Node(PlayerState.standing(Double.parseDouble(xyz[0]), Double.parseDouble(xyz[1]), Double.parseDouble(xyz[2]), 0),
                    new ArrayList<PlayerInput>(), Integer.MIN_VALUE / 2, 0, 0, null));
        } else if (in0) {
            open.add(new Node(s0, new ArrayList<PlayerInput>(), Integer.MIN_VALUE / 2, 0, 0, null));
        } else {
            // Into the start box first, landing there when it holds a floor.
            // Standing: a jump into the box can carry the player past everything ahead.
            Exit standIn = new Exit() {
                @Override
                public boolean reached(PlayerState before, PlayerState after) {
                    return after.onGround && start.contains(after.posX, after.posY, after.posZ);
                }

                @Override
                public double progress(PlayerState st, double along) {
                    return -Exit.distance(st.posX, st.posZ, start.minX, start.maxX, start.minZ, start.maxZ);
                }
            };
            Technique.Segment g = segment(s0, 1000, standIn, (start.minX + start.maxX) / 2, (start.minZ + start.maxZ) / 2,
                    deadlineMillis, segmentMillis);
            if (g == null) return null;
            Node n = advance(new Node(s0, new ArrayList<PlayerInput>(), Integer.MIN_VALUE / 2, -1, 0, null), g, null);
            if (n == null || n.entry < 0) return null;
            open.add(n);
        }
        if (System.getenv("EXPLORE_CHAIN") != null) {
            Route.Surface c = route.support(s0.posX, s0.posZ, s0.posY - 1e-6, s0.posY + 1e-6);
            for (String part : route.chainToGoal(c, 80).split(" -> ")) say("explore: chain " + part);
        }
        Set<Route.Surface> closed = new HashSet<Route.Surface>();
        Set<Long> places = new HashSet<Long>();
        double[] h = route.costToGoal();
        int expanded = 0;
        while (!open.isEmpty() && System.currentTimeMillis() < deadlineMillis) {
            Node n = open.poll();
            if (n.on != null && !closed.add(n.on)) continue;
            // One node per place: landings a block apart on the same floor lead to the same places.
            long cell = ((long) Math.floor(n.state.posX / 1.5) * 73856093L) ^ ((long) Math.floor(n.state.posZ / 1.5) * 19349663L)
                    ^ ((long) Math.floor(n.state.posY * 2) * 83492791L);
            if (!places.add(cell)) continue;
            expanded++;
            PlayerState s = n.state;
            int tick = n.inputs.size();
            int since = Math.max(0, Math.min(1000, tick - n.lastToggle));
            if (System.getenv("EXPLORE_CHAIN") != null && n.on != null && expanded % 8 == 0) say("explore: chain " + route.chainToGoal(n.on, 12));
            say("explore: node " + expanded + " at (" + fmt(s.posX) + ", " + fmt(s.posY) + ", " + fmt(s.posZ) + "), tick " + tick
                    + ", cost " + fmt(route.costToGoalAt(s.posX, s.posY, s.posZ, 1e-3)));
            // The goal, when near.
            double gx = Math.max(0, Math.max(goal.minX - s.posX, s.posX - goal.maxX));
            double gz = Math.max(0, Math.max(goal.minZ - s.posZ, s.posZ - goal.maxZ));
            if (Math.hypot(gx, gz) <= REACH && goal.minY <= s.posY + RISE) {
                Technique.Segment g = segment(s, since, Exit.box(goal.minX, goal.minY, goal.minZ, goal.maxX, goal.maxY, goal.maxZ),
                        (goal.minX + goal.maxX) / 2, (goal.minZ + goal.maxZ) / 2, deadlineMillis, segmentMillis);
                if (g != null) {
                    Node done = advance(n, g, null);
                    if (done != null) return done.inputs;
                }
            }
            List<Route.Surface> cands = candidates(s, n.on, closed, h);
            int landed = 0;
            for (int k = 0; k < cands.size(); k++) {
                Route.Surface c = cands.get(k);
                if (System.currentTimeMillis() >= deadlineMillis) break;
                // Onto a surface, or into a ladder, vine or water block.
                Exit e = c.climb ? Exit.box(c.x, c.y, c.z, c.x + 1, c.y + 1 - 1e-9, c.z + 1)
                        : Exit.landing(new Target(c.minX, c.maxX, c.minZ, c.maxZ, c.top));
                Route.Surface slime = via.get(c);
                Node m;
                if (slime != null) {
                    // Down onto the slime block first, then from the bounce to the landing.
                    m = bounce(n, since, slime, e, c, deadlineMillis, k < WIDE ? segmentMillis : 0);
                    if (m == null) continue;
                } else {
                    Technique.Segment g = segment(s, since, e, c.cx(), c.cz(), deadlineMillis,
                            k < WIDE ? segmentMillis : 0);
                    if (g == null) continue;
                    m = advance(n, g, c);
                }
                if (m != null) open.add(m);
                landed++;
            }
            say("explore:   " + cands.size() + " candidates, " + landed + " landed" + (System.getenv("EXPLORE_DEBUG") != null ? " " + cands : ""));
        }
        say("explore: gave up after " + expanded + " nodes");
        return null;
    }

    /** Surfaces not yet landed on within reach, lower in cost than here, cheapest first. */
    /** Of the last candidates, those reached by a bounce, with the slime block it lands on. */
    private final java.util.Map<Route.Surface, Route.Surface> via = new java.util.IdentityHashMap<Route.Surface, Route.Surface>();

    private List<Route.Surface> candidates(PlayerState s, Route.Surface on, Set<Route.Surface> closed, double[] h) {
        via.clear();
        double here = route.costToGoalAt(s.posX, s.posY, s.posZ, 1e-3);
        List<Route.Surface> out = new ArrayList<Route.Surface>();
        List<double[]> keys = new ArrayList<double[]>();
        List<Route.Surface> all = route.surfacesNear(s.posX, s.posZ, FAR + 1);
        for (Route.Surface c : all) {
            if (System.getenv("EXPLORE_WHY") != null && c.dist(s.posX, s.posZ) < 2.5) say("explore:     near " + c + " on " + (c == on) + " closed " + closed.contains(c) + " hc " + route.cost(c) + " here " + here);
            if (c == on || closed.contains(c)) continue;
            double hc = route.cost(c);
            if (!(hc < here - MIN_GAIN)) continue;
            double gap = c.dist(s.posX, s.posZ);
            double dy = c.top - s.posY;
            // Further down a drop, as far as the jump carries.
            if (gap > (dy < -1 ? Math.min(FAR, Math.max(REACH, route.jumpReach(dy))) : REACH)) continue;
            // The next block of the same floor is no step worth a search: the next one may be.
            if (!c.climb && Math.abs(dy) < 0.01 && gap < 2.0) continue;
            if (dy < -8 || dy > 8 || (dy > RISE && !route.climbAidNear(s.posX, s.posY, s.posZ, c))) continue;
            out.add(c);
            keys.add(new double[] {hc + gap / Route.RUN_SPEED, out.size() - 1});
            if (System.getenv("EXPLORE_WHY") != null) say("explore:     added " + c + " key " + (hc + gap / Route.RUN_SPEED));
        }
        // Up off a slime block, beyond what any jump reaches.
        Route.Surface under = on != null ? on : route.support(s.posX, s.posZ, s.posY - 1e-6, s.posY + 1e-6);
        if (under != null) {
            for (Route.Surface c : route.bounceTargets(under)) {
                if (closed.contains(c)) continue;
                double hc = route.cost(c);
                if (!(hc < here - MIN_GAIN)) continue;
                Route.Surface slime = route.bounceSlime(under, c);
                via.put(c, slime);
                // Nearest the slime block first: the bounce barely carries aside.
                double key = hc + BOUNCE_ASIDE * c.dist(slime.cx(), slime.cz());
                int i = out.indexOf(c);
                if (i < 0) {
                    out.add(c);
                    keys.add(new double[] {key, out.size() - 1});
                } else {
                    for (double[] k : keys) if ((int) k[1] == i) k[0] = key;
                }
            }
        }
        keys.sort(Comparator.comparingDouble(k -> k[0]));
        // Spread over heights: a row of blocks out of reach must not crowd out the floor ahead
        // (ladder, vine and water blocks count apart from the tops at their height).
        List<Route.Surface> best = new ArrayList<Route.Surface>();
        java.util.Map<Double, Integer> perTop = new java.util.HashMap<Double, Integer>();
        for (int i = 0; i < keys.size() && best.size() < FAN; i++) {
            Route.Surface c = out.get((int) keys.get(i)[1]);
            int k = perTop.merge(via.containsKey(c) ? -5000.0 : c.climb ? -1000.0 - c.y : c.top, 1, Integer::sum);
            if (k <= PER_TOP) best.add(c);
        }
        return best;
    }

    /** A bounce off {@code slime} that lands by {@code exit}: two segments, the second from the bounce. */
    private Node bounce(Node n, int since, Route.Surface slime, Exit exit, Route.Surface c, long deadlineMillis, long segmentMillis) {
        // Bouncing high enough for the landing: the fall onto the slime sets how high.
        double need = upSpeed((c.climb ? c.y : c.top) - slime.top + 0.2);
        double dx = c.cx() - slime.cx(), dz = c.cz() - slime.cz(), dn = Math.max(1e-9, Math.hypot(dx, dz));
        double ux = dx / dn, uz = dz / dn;
        Exit onSlime = new Exit() {
            @Override
            public boolean reached(PlayerState before, PlayerState after) {
                return before.motionY < 0 && after.motionY >= need && after.posY < slime.top + 1.5
                        && slime.contains(after.posX, after.posZ);
            }

            @Override
            public double progress(PlayerState st, double along) {
                // Higher at the same tick means a harder fall, and so a higher bounce; moving
                // towards the landing keeps that speed through the bounce.
                return -Exit.distance(st.posX, st.posZ, slime.minX, slime.maxX, slime.minZ, slime.maxZ) + 0.5 * st.posY
                        + 4 * (st.motionX * ux + st.motionZ * uz);
            }
        };
        // Aimed at the slime block's centre and at its side towards the landing.
        List<Technique.Segment> g1s = new ArrayList<Technique.Segment>();
        double[][] aims = {{slime.cx(), slime.cz()}, {slime.cx() + 0.4 * ux, slime.cz() + 0.4 * uz}, {slime.cx() - 0.4 * ux, slime.cz() - 0.4 * uz}};
        for (double[] aim : aims) {
            List<Technique.Segment> got = segments(n.state, since, onSlime, aim[0], aim[1], deadlineMillis, g1s.isEmpty() ? segmentMillis : 0);
            if (!got.isEmpty()) g1s.add(got.get(0));
        }
        if (System.getenv("EXPLORE_DEBUG") != null) say("explore:     bounce on " + slime + " towards " + c + ": " + (g1s.isEmpty() ? "no way onto it" : g1s.size() + " ways onto it"));
        // The landing depends on how the bounce starts: try a few ways onto the slime block.
        for (int k = 0; k < Math.min(BOUNCES, g1s.size()); k++) {
            Node mid = advance(n, g1s.get(k), null);
            if (mid == null) continue;
            int since2 = Math.max(0, Math.min(1000, mid.inputs.size() - mid.lastToggle));
            Technique.Segment g2 = segment(mid.state, since2, exit, c.cx(), c.cz(), deadlineMillis, k == 0 ? segmentMillis : 0);
            if (System.getenv("EXPLORE_DEBUG") != null) say("explore:     from the bounce at (" + fmt(mid.state.posX) + ", " + fmt(mid.state.posY) + ", " + fmt(mid.state.posZ) + "): " + (g2 == null ? "no landing" : "landed in " + g2.ticks()));
            if (g2 != null) return advance(mid, g2, c);
        }
        return null;
    }

    /** The upward speed that carries the player {@code rise} blocks up, with gravity and drag. */
    static double upSpeed(double rise) {
        for (double v = 0; ; v += 0.02) {
            double y = 0, w = v;
            while (w > 0) {
                y += w;
                w = (w - 0.08) * 0.98;
            }
            if (y >= rise) return v;
        }
    }

    /** The node after playing a segment from {@code n}; null when it breaks the toggle rule. */
    private Node advance(Node n, Technique.Segment g, Route.Surface on) {
        PlayerState s = n.state;
        int lastToggle = n.lastToggle, entry = n.entry, tick = n.inputs.size();
        boolean inStart = problem.start.contains(s.posX, s.posY, s.posZ);
        List<PlayerInput> inputs = new ArrayList<PlayerInput>(n.inputs);
        for (PlayerInput a : g.inputs) {
            tick++;
            PlayerState ns = Physics.tick(problem.world, s, a);
            if (ToggleRule.toggles(s, ns)) {
                if (!ToggleRule.allowed(lastToggle, tick)) return null;
                lastToggle = tick;
            }
            s = ns;
            inputs.add(a);
            boolean in = problem.start.contains(s.posX, s.posY, s.posZ);
            if (in && !inStart) entry = tick;
            inStart = in;
            if (entry >= 0 && problem.goal.contains(s.posX, s.posY, s.posZ)) break;
        }
        double hc = route.costToGoalAt(s.posX, s.posY, s.posZ, 1e-3);
        if (!(hc < Double.POSITIVE_INFINITY)) hc = 1e5;
        return new Node(s, inputs, lastToggle, entry, inputs.size() + hc, on);
    }

    private List<Technique.Segment> segments(PlayerState s, int since, Exit exit, double ax, double az, long deadlineMillis,
                                      long segmentMillis) {
        float heading = Technique.headingTo(s, ax, az);
        for (int pass = 0; pass < (segmentMillis > 0 ? 2 : 1); pass++) {
            long budget = Math.min(pass == 0 ? QUICK_MILLIS : segmentMillis, deadlineMillis - System.currentTimeMillis());
            if (budget <= 0) return java.util.Collections.emptyList();
            SegmentSearch.Options o = pass == 0 ? SegmentSearch.Options.fast() : new SegmentSearch.Options();
            o.maxTicks = pass == 0 ? 50 : 80;
            o.voidY = problem.voidY;
            if (pass == 1) {
                o.sneak = true;
                o.width = 4000;
            }
            List<Technique.Segment> segs = Technique.solve(problem.world, s, since, exit, heading, budget, o);
            if (!segs.isEmpty()) return segs;
        }
        return java.util.Collections.emptyList();
    }

    private Technique.Segment segment(PlayerState s, int since, Exit exit, double ax, double az, long deadlineMillis,
                                      long segmentMillis) {
        List<Technique.Segment> segs = segments(s, since, exit, ax, az, deadlineMillis, segmentMillis);
        return segs.isEmpty() ? null : segs.get(0);
    }

    private void say(String s) {
        if (log != null) log.say(s);
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
