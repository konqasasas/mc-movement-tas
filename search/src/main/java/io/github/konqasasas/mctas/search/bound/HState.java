package io.github.konqasasas.mctas.search.bound;

import java.util.ArrayList;
import java.util.List;

/**
 * A set of horizontal states, kept as four convex projections: position (x, z), velocity
 * (vx, vz), and (x, vx) and (z, vz). The set it stands for is every state whose four projections
 * lie in them, so each projection only has to be an outer bound on its own. The first two keep
 * the length limit of the acceleration (a disk, not a square); the last two keep how far a player
 * has moved along an axis tied to how fast they move along it.
 */
final class HState {

    static final HState EMPTY = new HState(Poly.EMPTY, Poly.EMPTY, CPoly.EMPTY, CPoly.EMPTY);

    final Poly pos;
    final Poly vel;
    final CPoly xv;
    final CPoly zv;

    private HState(Poly pos, Poly vel, CPoly xv, CPoly zv) {
        this.pos = pos;
        this.vel = vel;
        this.xv = xv;
        this.zv = zv;
    }

    static HState of(Poly pos, Poly vel, CPoly xv, CPoly zv) {
        return new HState(pos, vel, xv, zv).tighten();
    }

    static HState point(double x, double z, double vx, double vz) {
        return of(Poly.point(x, z), Poly.point(vx, vz), CPoly.point(x, vx), CPoly.point(z, vz));
    }

    boolean isEmpty() {
        return pos.isEmpty() || vel.isEmpty() || xv.isEmpty() || zv.isEmpty();
    }

    /**
     * Cuts each projection down to the ranges the others allow. Cuts shallower than {@link #SLACK}
     * are skipped; they would only cost time.
     */
    private HState tighten() {
        if (isEmpty()) return EMPTY;
        double x0 = Math.max(pos.minX(), xv.minU()), x1 = Math.min(pos.maxX(), xv.maxU());
        double z0 = Math.max(pos.minZ(), zv.minU()), z1 = Math.min(pos.maxZ(), zv.maxU());
        double a0 = Math.max(vel.minX(), xv.minW()), a1 = Math.min(vel.maxX(), xv.maxW());
        double b0 = Math.max(vel.minZ(), zv.minW()), b1 = Math.min(vel.maxZ(), zv.maxW());
        if (x0 > x1 + SLACK || z0 > z1 + SLACK || a0 > a1 + SLACK || b0 > b1 + SLACK) return EMPTY;
        Poly p = cuts(pos.minX(), x0, pos.maxX(), x1) || cuts(pos.minZ(), z0, pos.maxZ(), z1) ? pos.intersectRect(x0, x1, z0, z1) : pos;
        Poly v = cuts(vel.minX(), a0, vel.maxX(), a1) || cuts(vel.minZ(), b0, vel.maxZ(), b1) ? vel.intersectRect(a0, a1, b0, b1) : vel;
        CPoly cx = cuts(xv.minU(), x0, xv.maxU(), x1) || cuts(xv.minW(), a0, xv.maxW(), a1) ? xv.clipU(x0, x1).clipW(a0, a1) : xv;
        CPoly cz = cuts(zv.minU(), z0, zv.maxU(), z1) || cuts(zv.minW(), b0, zv.maxW(), b1) ? zv.clipU(z0, z1).clipW(b0, b1) : zv;
        HState h = new HState(p, v, cx, cz);
        return h.isEmpty() ? EMPTY : h;
    }

    private static final double SLACK = 1e-7;

    private static boolean cuts(double lo, double newLo, double hi, double newHi) {
        return newLo > lo + SLACK || newHi < hi - SLACK;
    }

    boolean contains(double x, double z, double vx, double vz, double tol) {
        return pos.contains(x, z, tol) && vel.contains(vx, vz, tol) && xv.contains(x, vx, tol) && zv.contains(z, vz, tol);
    }

    HState clipPos(double x0, double x1, double z0, double z1) {
        if (isEmpty()) return EMPTY;
        return of(pos.intersectRect(x0, x1, z0, z1), vel, xv.clipU(x0, x1), zv.clipU(z0, z1));
    }

    /** Keeps velocities with {@code vx >= 0} (0), {@code vx <= 0} (1), {@code vz >= 0} (2) or {@code vz <= 0} (3). */
    HState velSign(int face) {
        if (isEmpty()) return EMPTY;
        double inf = Double.POSITIVE_INFINITY;
        switch (face) {
            case 0: return of(pos, vel.halfPlane(0, 0), xv.clipW(0, inf), zv);
            case 1: return of(pos, vel.halfPlane(1, 0), xv.clipW(-inf, 0), zv);
            case 2: return of(pos, vel.halfPlane(2, 0), xv, zv.clipW(0, inf));
            default: return of(pos, vel.halfPlane(3, 0), xv, zv.clipW(-inf, 0));
        }
    }

    /** {@code EntityLivingBase}: velocity components under 0.003 may be set to 0. */
    HState cutoff() {
        if (isEmpty()) return EMPTY;
        Poly v = vel;
        CPoly cx = xv, cz = zv;
        if (v.xRangeTouches(-0.003D, 0.003D)) {
            v = v.hull(v.zeroX());
            cx = cx.withStopped();
        }
        if (v.zRangeTouches(-0.003D, 0.003D)) {
            v = v.hull(v.zeroZ());
            cz = cz.withStopped();
        }
        return new HState(pos, v, cx, cz);
    }

    /** Moves by the velocity plus an acceleration of length at most {@code r}, then applies friction {@code f}. */
    HState move(double r, double f) {
        if (isEmpty()) return EMPTY;
        Poly d = vel.plus(Poly.disk(r));
        return of(pos.plus(d), d.scale(f), xv.move(f, r), zv.move(f, r));
    }

    /** The displacement set of {@link #move}, before friction. */
    Poly displacement(double r) {
        return vel.plus(Poly.disk(r));
    }

    HState hull(HState o) {
        if (isEmpty()) return o;
        if (o.isEmpty()) return this;
        return new HState(pos.hull(o.pos), vel.hull(o.vel), xv.hull(o.xv), zv.hull(o.zv));
    }

    boolean covers(HState o) {
        if (o.isEmpty()) return true;
        return pos.covers(o.pos) && vel.covers(o.vel) && xv.covers(o.xv) && zv.covers(o.zv);
    }

    /**
     * States after a move that a block cut short: on a blocked axis the position ends between its
     * start and its wanted end and the velocity is 0; an axis that is not blocked ends like the
     * free move {@code to}. Returns the cases x blocked, z blocked, and both.
     */
    static List<HState> blocked(HState from, HState to) {
        List<HState> out = new ArrayList<HState>(3);
        if (from.isEmpty() || to.isEmpty()) return out;
        Poly box = from.pos.hull(to.pos).bbox();
        CPoly stopX = CPoly.rect(box.minX(), box.maxX(), 0, 0);
        CPoly stopZ = CPoly.rect(box.minZ(), box.maxZ(), 0, 0);
        add(out, of(Poly.rect(box.minX(), box.maxX(), to.pos.minZ(), to.pos.maxZ()), to.vel.zeroX(), stopX, to.zv));
        add(out, of(Poly.rect(to.pos.minX(), to.pos.maxX(), box.minZ(), box.maxZ()), to.vel.zeroZ(), to.xv, stopZ));
        add(out, of(box, Poly.point(0, 0), stopX, stopZ));
        return out;
    }

    /** The states with x set to a value in {@code [x0, x1]} and x velocity 0. */
    HState pinX(double x0, double x1) {
        if (isEmpty()) return EMPTY;
        return of(Poly.rect(x0, x1, pos.minZ(), pos.maxZ()), vel.zeroX(), CPoly.rect(x0, x1, 0, 0), zv);
    }

    /** The states with z set to a value in {@code [z0, z1]} and z velocity 0. */
    HState pinZ(double z0, double z1) {
        if (isEmpty()) return EMPTY;
        return of(Poly.rect(pos.minX(), pos.maxX(), z0, z1), vel.zeroZ(), xv, CPoly.rect(z0, z1, 0, 0));
    }

    private static void add(List<HState> out, HState h) {
        if (!h.isEmpty()) out.add(h);
    }

    /**
     * States after a move that sneaking at an edge shortened: positions anywhere between the start
     * and the wanted end on each axis, velocities as after the free move {@code to}.
     */
    static HState shortened(HState from, HState to) {
        if (from.isEmpty() || to.isEmpty()) return EMPTY;
        Poly box = from.pos.hull(to.pos).bbox();
        CPoly cx = CPoly.rect(box.minX(), box.maxX(), to.xv.minW(), to.xv.maxW());
        CPoly cz = CPoly.rect(box.minZ(), box.maxZ(), to.zv.minW(), to.zv.maxW());
        return of(box, to.vel, cx, cz);
    }

    /** States after a move that sneaking at an edge cut down to nothing: start positions, velocities after {@code to}. */
    static HState stayed(HState from, HState to) {
        if (from.isEmpty() || to.isEmpty()) return EMPTY;
        CPoly cx = CPoly.rect(from.pos.minX(), from.pos.maxX(), to.xv.minW(), to.xv.maxW());
        CPoly cz = CPoly.rect(from.pos.minZ(), from.pos.maxZ(), to.zv.minW(), to.zv.maxW());
        return of(from.pos, to.vel, cx, cz);
    }

    /** The join of {@code old} and {@code in}, rounded outward onto grids so repeated joins terminate. */
    static HState widenJoin(HState old, HState in) {
        if (old.isEmpty()) return in;
        if (in.isEmpty()) return old;
        return new HState(old.pos.hull(in.pos).roundUp(1e-3), old.vel.hull(in.vel).roundUp(1e-3),
            CPoly.widenJoin(old.xv, in.xv, 1, 0.1, 1e-3), CPoly.widenJoin(old.zv, in.zv, 1, 0.1, 1e-3));
    }

    /** True if both hold the same polygons (used to detect that a join changed nothing). */
    boolean sameAs(HState o) {
        return pos.sameAs(o.pos) && vel.sameAs(o.vel) && xv == o.xv && zv == o.zv;
    }

    double distance(HState o) {
        double d = 0;
        for (int i = 0; i < Poly.N; i++) {
            d += Math.abs(pos.support(i) - o.pos.support(i)) + 4 * Math.abs(vel.support(i) - o.vel.support(i));
        }
        return d;
    }

    @Override
    public String toString() {
        return "pos " + pos + " vel " + vel;
    }
}
