package io.github.konqasasas.mctas.improve;

import io.github.konqasasas.mctas.physics.PlayerInput;

import java.util.Random;

/**
 * Continuous tuning of the view over one stretch of the run with CMA-ES: the yaw of every tick in
 * the stretch gets an offset, interpolated linearly between a few control points, and the keys stay
 * as they are. The value is {@link Run#fitness()} (ticks, then slack), so the search pushes the run
 * towards saving the next tick even while the tick count stays the same.
 */
final class YawCma {
    int maxEvals = 240;

    Run apply(Course c, Run cur, Random rnd) {
        int goal = cur.goalTick;
        int lo = Math.max(0, cur.startTick - 12);
        if (goal - lo < 3) return null;
        int a = lo + rnd.nextInt(goal - lo - 1);
        int len = Math.min(goal - a, 4 + rnd.nextInt(60));
        int m = Math.max(1, Math.min(6, len / 4 + 1));
        double sigma0 = new double[] {0.3, 1.5, 6}[rnd.nextInt(3)];
        Cma cma = new Cma(new double[m], sigma0, rnd);
        Run best = null;
        double bestF = cur.fitness();
        int evals = 0;
        int stale = 0;
        while (evals < maxEvals && stale < 8 && cma.sigma > 1e-3) {
            double[][] xs = cma.ask();
            double[] f = new double[xs.length];
            boolean improved = false;
            for (int k = 0; k < xs.length; k++) {
                Run r = c.evaluate(cur, a, shifted(cur.inputs, a, len, xs[k]));
                evals++;
                // Failed runs rank by how far they got, so the search can still move.
                f[k] = r.valid ? r.fitness() : 1e6 - r.length;
                if (r.valid && f[k] < bestF - 1e-12) {
                    bestF = f[k];
                    best = r;
                    improved = true;
                }
            }
            cma.tell(xs, f);
            stale = improved ? 0 : stale + 1;
        }
        return best;
    }

    static PlayerInput[] shifted(PlayerInput[] in, int a, int len, double[] ctl) {
        PlayerInput[] out = new PlayerInput[in.length + Edits.PAD];
        System.arraycopy(in, 0, out, 0, in.length);
        for (int i = in.length; i < out.length; i++) out[i] = in[in.length - 1];
        int m = ctl.length;
        for (int t = 0; t < len; t++) {
            double d;
            if (m == 1) {
                d = ctl[0];
            } else {
                double u = (double) t / Math.max(1, len - 1) * (m - 1);
                int k = Math.min(m - 2, (int) u);
                double w = u - k;
                d = ctl[k] * (1 - w) + ctl[k + 1] * w;
            }
            PlayerInput x = in[a + t];
            out[a + t] = Edits.withYaw(x, x.yaw + (float) d);
        }
        return out;
    }
}
