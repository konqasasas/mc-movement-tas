package io.github.konqasasas.mctas.planner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * The learned guide's model (WS13): the probability that the A* actually makes an optimistic move
 * between platforms, from the move's {@link GuideFeatures}. Logistic regression on the features and
 * a few products of them, fitted on what earlier searches aimed at and made ({@link GuideData.Recorder}).
 */
final class GuideModel {
    final String[] names;
    final double[] mean, std, w;
    double bias;

    GuideModel(String[] names) {
        this.names = names;
        int n = names.length;
        this.mean = new double[n];
        this.std = new double[n];
        this.w = new double[n];
        java.util.Arrays.fill(std, 1);
    }

    /** The model's inputs from the move's features: the features, then the products that matter. */
    static double[] expand(double[] f) {
        double margin = f[2], runway = f[3];
        double[] x = java.util.Arrays.copyOf(f, f.length + 4);
        x[f.length] = margin * margin;
        x[f.length + 1] = Math.max(0, 2 - margin);
        x[f.length + 2] = Math.max(0, 2 - margin) * runway;
        x[f.length + 3] = Math.max(0, 2 - margin) * f[1];
        return x;
    }

    static String[] expandedNames() {
        String[] n = java.util.Arrays.copyOf(GuideFeatures.NAMES, GuideFeatures.N + 4);
        n[GuideFeatures.N] = "margin^2";
        n[GuideFeatures.N + 1] = "short";
        n[GuideFeatures.N + 2] = "short*runway";
        n[GuideFeatures.N + 3] = "short*dy";
        return n;
    }

    /** The probability that the move with features {@code f} ({@link GuideFeatures#of}) is made. */
    double made(double[] f) {
        double[] x = expand(f);
        double z = bias;
        for (int i = 0; i < names.length; i++) z += w[i] * (x[i] - mean[i]) / std[i];
        return 1 / (1 + Math.exp(-z));
    }

    /** Mean log-likelihood and the share right at one half. */
    double[] evaluate(List<double[]> fs, List<Integer> made) {
        double ll = 0;
        int right = 0;
        for (int i = 0; i < fs.size(); i++) {
            double p = Math.min(1 - 1e-9, Math.max(1e-9, made(fs.get(i))));
            int y = made.get(i);
            ll += y == 1 ? Math.log(p) : Math.log(1 - p);
            if ((p >= 0.5) == (y == 1)) right++;
        }
        return new double[] {ll / Math.max(1, fs.size()), right / (double) Math.max(1, fs.size())};
    }

    /** Fits by gradient descent on the full batch ({@code steps} steps, L2 weight {@code l2}). */
    static GuideModel fit(List<double[]> fs, List<Integer> made, int steps, double l2) {
        String[] names = expandedNames();
        GuideModel m = new GuideModel(names);
        int n = names.length, k = fs.size();
        double[][] x = new double[k][];
        for (int i = 0; i < k; i++) x[i] = expand(fs.get(i));
        for (int j = 0; j < n; j++) {
            double s = 0, q = 0;
            for (double[] r : x) {
                s += r[j];
                q += r[j] * r[j];
            }
            m.mean[j] = s / Math.max(1, k);
            double var = q / Math.max(1, k) - m.mean[j] * m.mean[j];
            m.std[j] = var > 1e-12 ? Math.sqrt(var) : 1;
        }
        for (double[] r : x) for (int j = 0; j < n; j++) r[j] = (r[j] - m.mean[j]) / m.std[j];
        double lr = 0.5;
        for (int it = 0; it < steps; it++) {
            double[] g = new double[n];
            double gb = 0;
            for (int i = 0; i < k; i++) {
                double z = m.bias;
                for (int j = 0; j < n; j++) z += m.w[j] * x[i][j];
                double d = 1 / (1 + Math.exp(-z)) - made.get(i);
                for (int j = 0; j < n; j++) g[j] += d * x[i][j];
                gb += d;
            }
            for (int j = 0; j < n; j++) m.w[j] -= lr * (g[j] / k + l2 * m.w[j]);
            m.bias -= lr * gb / k;
        }
        return m;
    }

    /** A plain text form: one line per array, the input names first. */
    String write() {
        StringBuilder b = new StringBuilder("mctas-guide 2\n");
        b.append("names ").append(String.join(" ", names)).append('\n');
        b.append("bias ").append(String.format(Locale.ROOT, "%.6g", bias)).append('\n');
        line(b, "mean", mean);
        line(b, "std", std);
        line(b, "w", w);
        return b.toString();
    }

    private static void line(StringBuilder b, String name, double[] v) {
        b.append(name);
        for (double x : v) b.append(' ').append(String.format(Locale.ROOT, "%.6g", x));
        b.append('\n');
    }

    static GuideModel read(String text) {
        String[] lines = text.split("\n");
        if (!lines[0].startsWith("mctas-guide 2")) throw new IllegalArgumentException("not a guide model of this version");
        String[] names = lines[1].substring("names ".length()).trim().split(" ");
        if (!java.util.Arrays.equals(names, expandedNames())) throw new IllegalArgumentException("the guide model has other inputs: " + lines[1]);
        GuideModel m = new GuideModel(names);
        m.bias = Double.parseDouble(lines[2].substring("bias ".length()).trim());
        nums(lines[3], "mean", m.mean);
        nums(lines[4], "std", m.std);
        nums(lines[5], "w", m.w);
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
