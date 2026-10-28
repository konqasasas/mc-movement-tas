package io.github.konqasasas.mctas.improve;

import java.util.Arrays;
import java.util.Random;

/**
 * A plain CMA-ES (Hansen's "purecma" with rank-one and rank-mu updates) for a handful of variables,
 * minimising. Used through {@link #ask} and {@link #tell}.
 */
final class Cma {
    final int n;
    final int lambda;
    private final int mu;
    private final double[] weights;
    private final double mueff, cc, cs, c1, cmu, damps, chiN;
    final double[] mean;
    double sigma;
    private final double[][] C;
    private final double[][] B;
    private final double[] D;
    private final double[] pc, ps;
    private int evalsSinceEigen;
    private int generation;
    private final Random rnd;

    Cma(double[] x0, double sigma0, Random rnd) {
        this.n = x0.length;
        this.rnd = rnd;
        this.lambda = 4 + (int) Math.floor(3 * Math.log(n));
        this.mu = lambda / 2;
        weights = new double[mu];
        double sum = 0;
        for (int i = 0; i < mu; i++) {
            weights[i] = Math.log(mu + 0.5) - Math.log(i + 1);
            sum += weights[i];
        }
        double sq = 0;
        for (int i = 0; i < mu; i++) {
            weights[i] /= sum;
            sq += weights[i] * weights[i];
        }
        mueff = 1 / sq;
        cc = (4 + mueff / n) / (n + 4 + 2 * mueff / n);
        cs = (mueff + 2) / (n + mueff + 5);
        c1 = 2 / ((n + 1.3) * (n + 1.3) + mueff);
        cmu = Math.min(1 - c1, 2 * (mueff - 2 + 1 / mueff) / ((n + 2) * (n + 2) + mueff));
        damps = 1 + 2 * Math.max(0, Math.sqrt((mueff - 1) / (n + 1)) - 1) + cs;
        chiN = Math.sqrt(n) * (1 - 1.0 / (4 * n) + 1.0 / (21 * n * n));
        mean = x0.clone();
        sigma = sigma0;
        C = identity(n);
        B = identity(n);
        D = new double[n];
        Arrays.fill(D, 1);
        pc = new double[n];
        ps = new double[n];
    }

    /** {@link #lambda} new points. */
    double[][] ask() {
        double[][] xs = new double[lambda][n];
        for (int k = 0; k < lambda; k++) {
            double[] z = new double[n];
            for (int i = 0; i < n; i++) z[i] = D[i] * rnd.nextGaussian();
            for (int i = 0; i < n; i++) {
                double s = 0;
                for (int j = 0; j < n; j++) s += B[i][j] * z[j];
                xs[k][i] = mean[i] + sigma * s;
            }
        }
        return xs;
    }

    /** Updates from the points of the last {@link #ask} and their values (lower is better). */
    void tell(double[][] xs, double[] f) {
        Integer[] idx = new Integer[xs.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, (p, q) -> Double.compare(f[p], f[q]));
        double[] old = mean.clone();
        for (int i = 0; i < n; i++) {
            double s = 0;
            for (int k = 0; k < mu; k++) s += weights[k] * xs[idx[k]][i];
            mean[i] = s;
        }
        double[] y = new double[n];
        for (int i = 0; i < n; i++) y[i] = (mean[i] - old[i]) / sigma;
        // C^-1/2 * y = B * D^-1 * B^T * y
        double[] bty = new double[n];
        for (int j = 0; j < n; j++) {
            double s = 0;
            for (int i = 0; i < n; i++) s += B[i][j] * y[i];
            bty[j] = s / D[j];
        }
        double[] z = new double[n];
        for (int i = 0; i < n; i++) {
            double s = 0;
            for (int j = 0; j < n; j++) s += B[i][j] * bty[j];
            z[i] = s;
        }
        double csn = Math.sqrt(cs * (2 - cs) * mueff);
        double psNorm = 0;
        for (int i = 0; i < n; i++) {
            ps[i] = (1 - cs) * ps[i] + csn * z[i];
            psNorm += ps[i] * ps[i];
        }
        psNorm = Math.sqrt(psNorm);
        generation++;
        boolean hsig = psNorm / Math.sqrt(1 - Math.pow(1 - cs, 2 * generation)) / chiN < 1.4 + 2.0 / (n + 1);
        double ccn = Math.sqrt(cc * (2 - cc) * mueff);
        for (int i = 0; i < n; i++) pc[i] = (1 - cc) * pc[i] + (hsig ? ccn * y[i] : 0);
        double c1a = c1 * (1 - (hsig ? 0 : 1) * cc * (2 - cc));
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double rankMu = 0;
                for (int k = 0; k < mu; k++) {
                    double[] x = xs[idx[k]];
                    rankMu += weights[k] * (x[i] - old[i]) / sigma * (x[j] - old[j]) / sigma;
                }
                double v = (1 - c1a - cmu) * C[i][j] + c1 * pc[i] * pc[j] + cmu * rankMu;
                C[i][j] = v;
                C[j][i] = v;
            }
        }
        sigma *= Math.exp(Math.min(1, (cs / damps) * (psNorm / chiN - 1)));
        evalsSinceEigen += xs.length;
        if (evalsSinceEigen > lambda / (c1 + cmu) / n / 10) {
            evalsSinceEigen = 0;
            eigen();
        }
    }

    /** Jacobi eigen-decomposition of C into B and D (square roots of the eigenvalues). */
    private void eigen() {
        double[][] a = new double[n][];
        for (int i = 0; i < n; i++) a[i] = C[i].clone();
        double[][] v = identity(n);
        for (int sweep = 0; sweep < 60; sweep++) {
            double off = 0;
            for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) off += a[i][j] * a[i][j];
            if (off < 1e-22) break;
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    if (Math.abs(a[p][q]) < 1e-300) continue;
                    double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
                    double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                    if (theta == 0) t = 1;
                    double cos = 1 / Math.sqrt(t * t + 1), sin = t * cos;
                    for (int k = 0; k < n; k++) {
                        double akp = a[k][p], akq = a[k][q];
                        a[k][p] = cos * akp - sin * akq;
                        a[k][q] = sin * akp + cos * akq;
                    }
                    for (int k = 0; k < n; k++) {
                        double apk = a[p][k], aqk = a[q][k];
                        a[p][k] = cos * apk - sin * aqk;
                        a[q][k] = sin * apk + cos * aqk;
                    }
                    for (int k = 0; k < n; k++) {
                        double vkp = v[k][p], vkq = v[k][q];
                        v[k][p] = cos * vkp - sin * vkq;
                        v[k][q] = sin * vkp + cos * vkq;
                    }
                }
            }
        }
        for (int i = 0; i < n; i++) {
            D[i] = Math.sqrt(Math.max(1e-20, a[i][i]));
            for (int k = 0; k < n; k++) B[k][i] = v[k][i];
        }
    }

    private static double[][] identity(int n) {
        double[][] m = new double[n][n];
        for (int i = 0; i < n; i++) m[i][i] = 1;
        return m;
    }
}
