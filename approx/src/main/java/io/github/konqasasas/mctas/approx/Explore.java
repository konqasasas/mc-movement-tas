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
        Node done = search(open, null, deadlineMillis, segmentMillis);
        return done == null ? null : done.inputs;
    }

    /**
     * From part way along: {@code inputs} from the spawn lead to {@code s}. Searches on to the first
     * landing that {@code done} accepts (or into the goal), and returns the inputs from the spawn up to
     * there; null when the deadline comes first or nothing is left.
     */
    List<PlayerInput> from(PlayerState s, List<PlayerInput> inputs, int lastToggle, int entry,
                           java.util.function.Predicate<PlayerState> done, long deadlineMillis, long segmentMillis) {
        PriorityQueue<Node> open = new PriorityQueue<Node>(Comparator.comparingDouble(n -> n.f));
        open.add(new Node(s, new ArrayList<PlayerInput>(inputs), lastToggle, entry, 0, null));
        Node n = search(open, done, deadlineMillis, segmentMillis);
        return n == null ? null : n.inputs;
    }

    /** Best first from the nodes in {@code open}, until a node in the goal or one {@code done} accepts. */
    private Node search(PriorityQueue<Node> open, java.util.function.Predicate<PlayerState> stop, long deadlineMillis,
                        long segmentMillis) {
        Region goal = problem.goal;
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
                    if (done != null) return done;
                }
            }
            List<Route.Surface> cands = candidates(s, n.on, closed, h);
            int landed = 0;
            for (int k = 0; k < cands.size(); k++) {
                Route.Surface c = cands.get(k);
                if (System.currentTimeMillis() >= deadlineMillis) break;
                // Onto a surface, or into a ladder, vine or water block.
                Exit e = c.climb ? Exit.box(c.x, c.y, c.z, c.x + 1, c.y + 1 - 1e-9, c.z + 1)
                        : landing(c);
                Route.Surface slime = via.get(c);
                Node m;
                if (slime != null) {
                    // Down onto the slime block first, then from the bounce to the landing.
                    m = bounce(n, since, slime, e, c, deadlineMillis, k < WIDE ? segmentMillis : 0);
                    if (m == null) continue;
                } else {
                    // Up a ladder or vine: to the top of it in one go when that works, a block a node is slow.
                    Route.Surface t = c.climb && !Route.isWater(problem.world.getBlock(c.x, c.y, c.z)) ? route.climbTop(c) : c;
                    Technique.Segment g = null;
                    if (t != c && !closed.contains(t)) {
                        g = segment(s, since, Exit.box(t.x, t.y, t.z, t.x + 1, t.y + 1 - 1e-9, t.z + 1), t.cx(), t.cz(),
                                deadlineMillis, k < WIDE ? segmentMillis : 0);
                        if (g != null) c = t;
                    }
                    if (g == null) g = segment(s, since, e, c.cx(), c.cz(), deadlineMillis, k < WIDE ? segmentMillis : 0);
                    // Onto a post at speed after all: a jump on from the landing tick may still carry on.
                    if (g == null && !c.climb && narrow(c)) {
                        g = segment(s, since, Exit.landing(target(c)), c.cx(), c.cz(), deadlineMillis, 0);
                    }
                    if (g != null) {
                        m = advance(n, g, c);
                    } else if (k < WIDE && n.on != null && !n.on.climb) {
                        m = runUp(n, e, c, deadlineMillis);
                    } else {
                        continue;
                    }
                }
                if (m == null) continue;
                if (stop != null && stop.test(m.state)) return m;
                open.add(m);
                landed++;
            }
            say("explore:   " + cands.size() + " candidates, " + landed + " landed" + (System.getenv("EXPLORE_DEBUG") != null ? " " + cands : ""));
        }
        say("explore: gave up after " + expanded + " nodes");
        return null;
    }

    /** Of the last candidates, those reached by a bounce, with the slime block it lands on. */
    private final java.util.Map<Route.Surface, Route.Surface> via = new java.util.IdentityHashMap<Route.Surface, Route.Surface>();

    /** Surfaces not yet landed on within reach, lower in cost than here, cheapest first. */
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
            // (Only along a floor: a row of posts or wall tops a block apart is a row of jumps.)
            if (!c.climb && Math.abs(dy) < 0.01 && gap < 2.0 && floorTo(s, c)) continue;
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

    /**
     * Back to the far side of the surface underfoot, standing nearly still, then the jump from there:
     * a landing short of a post or wall top often has too little room left for the run-up.
     */
    private Node runUp(Node n, Exit exit, Route.Surface c, long deadlineMillis) {
        PlayerState s = n.state;
        Target on = target(n.on);
        // A block back from the middle, away from the landing, as far as the surface reaches.
        double dx = c.cx() - n.on.cx(), dz = c.cz() - n.on.cz(), d = Math.hypot(dx, dz);
        if (d < 1e-6) return null;
        double bx = Math.max(on.minX + 0.15, Math.min(on.maxX - 0.15, n.on.cx() - dx / d));
        double bz = Math.max(on.minZ + 0.15, Math.min(on.maxZ - 0.15, n.on.cz() - dz / d));
        if (Math.hypot(bx - s.posX, bz - s.posZ) < 0.15) return null;
        // Walk there and let friction stop the player, no sprinting.
        boolean narrow = narrow(n.on);
        List<PlayerInput> walk = new ArrayList<PlayerInput>();
        PlayerState w = s;
        for (int t = 0; t < 40; t++) {
            double ex = bx - w.posX, ez = bz - w.posZ, v = Math.hypot(w.motionX, w.motionZ);
            if (Math.hypot(ex, ez) < 0.2 && v < 0.02) break;
            boolean go = Math.hypot(ex, ez) > v * 2.2 + 0.05;
            // Sneaking on a post or wall top, which holds the player at its edge.
            PlayerInput a = PlayerInput.of((go ? PlayerInput.W : 0) | (narrow ? PlayerInput.SNEAK : 0),
                    (float) Math.toDegrees(Math.atan2(-ex, ez)));
            w = Physics.tick(problem.world, w, a);
            walk.add(a);
            if (!w.onGround || Math.abs(w.posY - n.on.top) > 1e-6) {
                say("explore:     no run-up back to (" + fmt(bx) + ", " + fmt(bz) + ") for " + c + ": off at tick " + t + " (" + fmt(w.posX) + ", " + fmt(w.posY) + ", " + fmt(w.posZ) + ") from (" + fmt(s.posX) + ", " + fmt(s.posZ) + ") v " + fmt(s.motionX) + "," + fmt(s.motionZ) + " g " + s.onGround);
                return null;
            }
        }
        Node b = advance(n, walk, n.on);
        if (b == null) return null;
        int since = Math.max(0, Math.min(1000, b.inputs.size() - b.lastToggle));
        Technique.Segment j = segment(b.state, since, exit, c.cx(), c.cz(), deadlineMillis, 0);
        say("explore:     run-up from (" + fmt(bx) + ", " + fmt(bz) + ") to " + c + (j == null ? " failed" : " in " + j.inputs.size() + " ticks"));
        return j == null ? null : advance(b, j, c);
    }

    /**
     * Where the feet may be to stand on {@code c}: its footprint (grown by the half width) less a
     * sliver, since a landing on the very edge counts as on the ground for that one tick only.
     */
    static Target target(Route.Surface c) {
        return new Target(c.minX + 0.02, c.maxX - 0.02, c.minZ + 0.02, c.maxZ - 0.02, c.top);
    }

    /**
     * Landing on {@code c}. On a post or wall top, also slow enough to stay on it: a landing at the far
     * edge at full speed leaves no tick to jump on from that a search finds.
     */
    static Exit landing(Route.Surface c) {
        Target t = target(c);
        Exit e = Exit.landing(t);
        if (!narrow(c)) return e;
        return new Exit() {
            @Override
            public boolean reached(PlayerState before, PlayerState after) {
                if (!e.reached(before, after)) return false;
                // Where friction stops the player with no input: about 1.2 ticks of the speed further.
                double x = after.posX + 1.2 * after.motionX, z = after.posZ + 1.2 * after.motionZ;
                return x >= t.minX && x <= t.maxX && z >= t.minZ && z <= t.maxZ;
            }

            @Override
            public double progress(PlayerState s, double along) {
                return e.progress(s, along);
            }
        };
    }

    /** A post or wall top: narrower than a block either way (footprints are grown by the half width). */
    static boolean narrow(Route.Surface c) {
        return c.maxX - c.minX < 1.5 || c.maxZ - c.minZ < 1.5;
    }

    /** True when the floor under the player runs on to {@code c}'s centre at the same height. */
    private boolean floorTo(PlayerState s, Route.Surface c) {
        double dx = c.cx() - s.posX, dz = c.cz() - s.posZ;
        int n = (int) Math.ceil(Math.hypot(dx, dz) / 0.25);
        for (int i = 1; i < n; i++) {
            if (route.support(s.posX + dx * i / n, s.posZ + dz * i / n, c.top - 1e-6, c.top + 1e-6) == null) return false;
        }
        return true;
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
        return advance(n, g.inputs, on);
    }

    private Node advance(Node n, List<PlayerInput> g, Route.Surface on) {
        PlayerState s = n.state;
        int lastToggle = n.lastToggle, entry = n.entry, tick = n.inputs.size();
        boolean inStart = problem.start.contains(s.posX, s.posY, s.posZ);
        List<PlayerInput> inputs = new ArrayList<PlayerInput>(n.inputs);
        for (PlayerInput a : g) {
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
            long t0 = System.currentTimeMillis();
            List<Technique.Segment> segs = Technique.solve(problem.world, s, since, exit, heading, budget, o);
            if (System.getenv("EXPLORE_TIME") != null) say("explore:     segment pass " + pass + " budget " + budget + " took " + (System.currentTimeMillis() - t0) + " found " + segs.size());
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
