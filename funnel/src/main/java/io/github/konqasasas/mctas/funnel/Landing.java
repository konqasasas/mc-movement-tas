package io.github.konqasasas.mctas.funnel;

import io.github.konqasasas.mctas.physics.PlayerState;

import java.util.Arrays;

/**
 * The field's value for a player in the air: where the jump or fall can come down.
 *
 * <p>The cell field lets the player cross the air at any level near a stand, as if hovering, so a
 * falling player would look as good as one standing at the same place. Instead, the flight is
 * followed without input (gravity, drag 0.91) for up to {@link #TICKS} ticks; on each tick that comes
 * down through a level, the stands (and ladders and water) of that level within the reach of air
 * control around the flight are candidates, at the tick plus their field value. The best candidate is
 * the value; none (a fall into the void) is infinite.
 *
 * <p>The reach grows with the tick (the most the air acceleration moves the player off the flight,
 * plus 0.2), in three steps; each step has a min-filtered copy of the stand
 * values (square windows), rebuilt by {@link #refresh} after the field changes.
 */
final class Landing {
    static final int TICKS = 40;
    private static final int[] RADII = {2, 6, 12};
    /** The most air control moves the player off the flight in k ticks (sprinting, 0.026 per tick, drag 0.91), plus a margin. */
    private static final double[] REACH = new double[TICKS + 1];

    static {
        double v = 0, d = 0;
        for (int k = 1; k <= TICKS; k++) {
            v += 0.026;
            d += v;
            v *= 0.91;
            REACH[k] = d + 0.2;
        }
    }

    private final Grid g;
    private final Field f;
    private final float[][] min = new float[RADII.length][];
    /** Levels with a stand, ladder or water cell; with a stand on slime. */
    private final boolean[] hasLanding, hasSlime;

    Landing(Grid g, Field f) {
        this.g = g;
        this.f = f;
        hasLanding = new boolean[g.ny];
        hasSlime = new boolean[g.ny];
        for (int i = 0; i < RADII.length; i++) min[i] = new float[g.flags.length];
        for (int id = 0; id < g.flags.length; id++) {
            if ((g.flags[id] & (Grid.STAND | Grid.CLIMB | Grid.WATER)) != 0) hasLanding[g.level(id)] = true;
            if ((g.flags[id] & Grid.SLIME) != 0) hasSlime[g.level(id)] = true;
        }
        refresh();
    }

    void refresh() {
        int nx = g.nx, nz = g.nz;
        float[] row = new float[Math.max(nx, nz)], out = new float[Math.max(nx, nz)];
        float[] base = new float[nx * nz];
        for (int r = 0; r < RADII.length; r++) {
            float[] m = min[r];
            int rad = RADII[r];
            for (int l = 0; l < g.ny; l++) {
                int off = l * nx * nz;
                if (!hasLanding[l]) {
                    Arrays.fill(m, off, off + nx * nz, Field.INF);
                    continue;
                }
                for (int k = 0; k < nx * nz; k++) {
                    int id = off + k;
                    base[k] = (g.flags[id] & (Grid.STAND | Grid.CLIMB | Grid.WATER)) != 0 ? f.d[id] : Field.INF;
                }
                // Rows along z, then columns along x.
                for (int ix = 0; ix < nx; ix++) {
                    System.arraycopy(base, ix * nz, row, 0, nz);
                    window(row, out, nz, rad);
                    System.arraycopy(out, 0, base, ix * nz, nz);
                }
                for (int iz = 0; iz < nz; iz++) {
                    for (int ix = 0; ix < nx; ix++) row[ix] = base[ix * nz + iz];
                    window(row, out, nx, rad);
                    for (int ix = 0; ix < nx; ix++) m[off + ix * nz + iz] = out[ix];
                }
            }
        }
    }

    /** Sliding minimum over [i - r, i + r]. */
    private static void window(float[] in, float[] out, int n, int r) {
        int[] dq = new int[n];
        int head = 0, tail = 0;
        int j = 0;
        for (int i = 0; i < n; i++) {
            int hi = Math.min(n - 1, i + r);
            while (j <= hi) {
                while (tail > head && in[dq[tail - 1]] >= in[j]) tail--;
                dq[tail++] = j++;
            }
            while (dq[head] < i - r) head++;
            out[i] = in[dq[head]];
        }
    }

    /** Ticks to go for a player in the air: the best landing along the flight. */
    float value(PlayerState s) {
        double x = s.posX, y = s.posY, z = s.posZ, vx = s.motionX, vy = s.motionY, vz = s.motionZ;
        float best = Field.INF;
        // How high the flight was: the fall so far, or the top still to come.
        double apex = y + Math.max(0, s.fallDistance);
        for (int k = 1; k <= TICKS; k++) {
            double py = y;
            y += vy;
            apex = Math.max(apex, y);
            x += vx;
            z += vz;
            vy = (vy - 0.08) * 0.98;
            vx *= 0.91;
            vz *= 0.91;
            if (y < g.problem.voidY || y < g.y0) break;
            if (best <= k) break;
            if (y >= py) continue;
            int lHi = (int) Math.floor(py - g.y0 + 1e-7), lLo = (int) Math.floor(y - g.y0 + 1e-7);
            double reach = REACH[k];
            int r = reach <= 0.5 ? 0 : reach <= 1.5 ? 1 : 2;
            int ix = (int) Math.round((x - g.x0) / Grid.RES), iz = (int) Math.round((z - g.z0) / Grid.RES);
            if (ix < 0 || iz < 0 || ix >= g.nx || iz >= g.nz) continue;
            for (int l = Math.max(0, lLo); l <= Math.min(g.ny - 1, lHi); l++) {
                if (!hasLanding[l]) continue;
                float v = min[r][g.id(l, ix, iz)];
                if (v + k < best) best = v + k;
                if (hasSlime[l]) {
                    float b = bounce(l, ix, iz, RADII[r], apex);
                    if (b + k < best) best = b + k;
                }
            }
        }
        return best;
    }

    /**
     * Coming down on slime within {@code rad} cells (the reach of air control): the bounce goes back up about as high as the
     * flight was, so the best cell of the column above the slime up to the flight's top.
     */
    private float bounce(int l, int ix, int iz, int rad, double apex) {
        float best = Field.INF;
        int top = Math.min(g.ny - 1, (int) Math.floor(apex - g.y0 + 1e-7));
        for (int jx = Math.max(0, ix - rad); jx <= Math.min(g.nx - 1, ix + rad); jx++) {
            for (int jz = Math.max(0, iz - rad); jz <= Math.min(g.nz - 1, iz + rad); jz++) {
                if ((g.flags[g.id(l, jx, jz)] & Grid.SLIME) == 0) continue;
                for (int lv = l + 1; lv <= top; lv++) {
                    int id = g.id(lv, jx, jz);
                    if ((g.flags[id] & Grid.PASS) == 0) break;
                    best = Math.min(best, f.d[id]);
                }
            }
        }
        return best;
    }
}
