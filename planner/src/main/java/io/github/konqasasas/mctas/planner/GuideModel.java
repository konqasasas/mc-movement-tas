package io.github.konqasasas.mctas.planner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The learned guide's model (WS13): a correction, in ticks, to the A* priority of a node, from its
 * {@link GuideFeatures#node} features. A linear part plus a small hidden layer of {@code tanh} units,
 * fitted on pairs from earlier searches: a node of the plan found and a node off it that was expanded
 * (or left) while the plan's node waited, so that the plan's node comes first.
 */
final class GuideModel {
    final String[] names;
    final double[] mean, std;
    final int hidden;
    /** Linear weights; hidden weights (hidden x n), biases and output weights. */
    final double[] w;
    final double[][] w1;
    final double[] b1, w2;

    GuideModel(String[] names, int hidden) {
        this.names = names;
        int n = names.length;
        this.mean = new double[n];
        this.std = new double[n];
        java.util.Arrays.fill(std, 1);
        this.hidden = hidden;
        this.w = new double[n];
        this.w1 = new double[hidden][n];
        this.b1 = new double[hidden];
        this.w2 = new double[hidden];
    }

    double score(double[] f) {
        int n = names.length;
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = (f[i] - mean[i]) / std[i];
        double s = 0;
        for (int i = 0; i < n; i++) s += w[i] * x[i];
        for (int j = 0; j < hidden; j++) {
            double a = b1[j];
            for (int i = 0; i < n; i++) a += w1[j][i] * x[i];
            s += w2[j] * Math.tanh(a);
        }
        return s;
    }

    /** Temperature of the pair loss, in ticks of priority. */
    static final double T = 5;

    /**
     * The share of pairs the corrected priority orders right (the plan's node first), and the mean
     * loss. A pair is {plan's node, other node}, each {clock, bound, features...}; the priority is
     * clock + {@code weight} * bound + correction.
     */
    double[] evaluate(List<double[][]> pairs, double weight) {
        int right = 0;
        double loss = 0;
        for (double[][] p : pairs) {
            double z = (prio(p[1], weight) - prio(p[0], weight)) / T;
            if (z > 0) right++;
            loss += softplus(-z);
        }
        return new double[] {right / (double) Math.max(1, pairs.size()), loss / Math.max(1, pairs.size())};
    }

    private double prio(double[] row, double weight) {
        return row[0] + weight * row[1] + score(java.util.Arrays.copyOfRange(row, 2, row.length));
    }

    private static double softplus(double x) {
        return x > 30 ? x : Math.log1p(Math.exp(x));
    }

    /**
     * Fits the correction to pairs with Adam ({@code epochs} passes, L2 weight {@code l2}): a logistic
     * loss on the priority of the other node minus that of the plan's node.
     */
    static GuideModel fit(String[] names, int hidden, List<double[][]> pairs, double weight, int epochs, double l2, long seed) {
        GuideModel m = new GuideModel(names, hidden);
        int n = names.length;
        long count = 0;
        double[] sum = new double[n], sq = new double[n];
        for (double[][] p : pairs) {
            for (double[] r : p) {
                for (int i = 0; i < n; i++) {
                    sum[i] += r[i + 2];
                    sq[i] += r[i + 2] * r[i + 2];
                }
                count++;
            }
        }
        for (int i = 0; i < n; i++) {
            m.mean[i] = sum[i] / Math.max(1, count);
            double var = sq[i] / Math.max(1, count) - m.mean[i] * m.mean[i];
            m.std[i] = var > 1e-12 ? Math.sqrt(var) : 1;
        }
        Random rnd = new Random(seed);
        for (int j = 0; j < hidden; j++) {
            for (int i = 0; i < n; i++) m.w1[j][i] = rnd.nextGaussian() * 0.3;
            m.w2[j] = rnd.nextGaussian() * 0.1;
        }
        int np = n + hidden * n + 2 * hidden;
        double[] mo = new double[np], ve = new double[np];
        double lr = 0.01, beta1 = 0.9, beta2 = 0.999;
        int step = 0;
        List<Integer> order = new ArrayList<Integer>();
        for (int i = 0; i < pairs.size(); i++) order.add(i);
        int batch = 256;
        for (int ep = 0; ep < epochs; ep++) {
            java.util.Collections.shuffle(order, rnd);
            for (int b0 = 0; b0 < order.size(); b0 += batch) {
                double[] grad = new double[np];
                int b1n = Math.min(order.size(), b0 + batch);
                for (int bi = b0; bi < b1n; bi++) {
                    double[][] p = pairs.get(order.get(bi));
                    double z = (m.prio(p[1], weight) - m.prio(p[0], weight)) / T;
                    // d softplus(-z) / dz = -sigmoid(-z); z grows with the other's correction, falls with the plan's.
                    double d = 1 / (1 + Math.exp(z)) / T;
                    m.addGradient(p[1], -d, grad);
                    m.addGradient(p[0], d, grad);
                }
                double scale = 1.0 / (b1n - b0);
                double[] pv = m.params();
                step++;
                for (int k = 0; k < np; k++) {
                    double gk = grad[k] * scale + l2 * pv[k];
                    mo[k] = beta1 * mo[k] + (1 - beta1) * gk;
                    ve[k] = beta2 * ve[k] + (1 - beta2) * gk * gk;
                    double mh = mo[k] / (1 - Math.pow(beta1, step)), vh = ve[k] / (1 - Math.pow(beta2, step));
                    pv[k] -= lr * mh / (Math.sqrt(vh) + 1e-8);
                }
                m.setParams(pv);
            }
        }
        return m;
    }

    /** Adds {@code d} times the gradient of the correction at {@code row} (clock, bound, features...). */
    private void addGradient(double[] row, double d, double[] grad) {
        int n = names.length;
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = (row[i + 2] - mean[i]) / std[i];
        for (int i = 0; i < n; i++) grad[i] += d * x[i];
        int o = n;
        for (int j = 0; j < hidden; j++) {
            double z = b1[j];
            for (int i = 0; i < n; i++) z += w1[j][i] * x[i];
            double act = Math.tanh(z);
            double dz = d * w2[j] * (1 - act * act);
            for (int i = 0; i < n; i++) grad[o + j * n + i] += dz * x[i];
            grad[o + hidden * n + j] += dz;
            grad[o + hidden * n + hidden + j] += d * act;
        }
    }

    private double[] params() {
        int n = names.length;
        double[] p = new double[n + hidden * n + 2 * hidden];
        System.arraycopy(w, 0, p, 0, n);
        for (int j = 0; j < hidden; j++) System.arraycopy(w1[j], 0, p, n + j * n, n);
        System.arraycopy(b1, 0, p, n + hidden * n, hidden);
        System.arraycopy(w2, 0, p, n + hidden * n + hidden, hidden);
        return p;
    }

    private void setParams(double[] p) {
        int n = names.length;
        System.arraycopy(p, 0, w, 0, n);
        for (int j = 0; j < hidden; j++) System.arraycopy(p, n + j * n, w1[j], 0, n);
        System.arraycopy(p, n + hidden * n, b1, 0, hidden);
        System.arraycopy(p, n + hidden * n + hidden, w2, 0, hidden);
    }

    /** A plain text form: one line per array, the feature names first. */
    String write() {
        StringBuilder b = new StringBuilder("mctas-guide 1\n");
        b.append("names ").append(String.join(" ", names)).append('\n');
        b.append("hidden ").append(hidden).append('\n');
        line(b, "mean", mean);
        line(b, "std", std);
        line(b, "w", w);
        for (int j = 0; j < hidden; j++) line(b, "w1", w1[j]);
        line(b, "b1", b1);
        line(b, "w2", w2);
        return b.toString();
    }

    private static void line(StringBuilder b, String name, double[] v) {
        b.append(name);
        for (double x : v) b.append(' ').append(String.format(Locale.ROOT, "%.6g", x));
        b.append('\n');
    }

    static GuideModel read(String text) {
        String[] lines = text.split("\n");
        if (!lines[0].startsWith("mctas-guide 1")) throw new IllegalArgumentException("not a guide model");
        String[] names = lines[1].substring("names ".length()).trim().split(" ");
        int hidden = Integer.parseInt(lines[2].substring("hidden ".length()).trim());
        GuideModel m = new GuideModel(names, hidden);
        int li = 3;
        nums(lines[li++], "mean", m.mean);
        nums(lines[li++], "std", m.std);
        nums(lines[li++], "w", m.w);
        for (int j = 0; j < hidden; j++) nums(lines[li++], "w1", m.w1[j]);
        nums(lines[li++], "b1", m.b1);
        nums(lines[li++], "w2", m.w2);
        return m;
    }

    private static void nums(String line, String name, double[] into) {
        String[] p = line.trim().split(" ");
        if (!p[0].equals(name) || p.length - 1 != into.length) throw new IllegalArgumentException("bad line for " + name + ": " + line);
        for (int i = 0; i < into.length; i++) into[i] = Double.parseDouble(p[i + 1]);
    }

    static GuideModel read(Path p) throws IOException {
        return read(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
    }
}
