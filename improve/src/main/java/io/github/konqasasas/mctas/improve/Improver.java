package io.github.konqasasas.mctas.improve;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/**
 * The anytime improver: a small population of whole runs, and worker threads that keep taking a run
 * from it, changing it with one of the moves, and putting better runs back.
 *
 * <p>Moves: {@link Micro} (small edits, thousands per second), {@link Rebuild} (rebuild a stretch
 * with the exact local search, the large-neighbourhood move), {@link YawCma} (CMA-ES on the view of
 * a stretch) and {@link Crossover} (join two runs of the population). Each thread walks from its own
 * current run with late acceptance (it also takes a run no better than the one it had some steps
 * ago), so it can drift along runs of the same tick count, and goes back to a population run when it
 * has not improved for a while. Every move judges whole runs with {@link Course#evaluate}, so every
 * run in the population is valid as the judge sees it.
 */
public final class Improver {
    public enum Move { MICRO, REBUILD, CMA, CROSSOVER }

    public static final class Options {
        public int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        public int population = 8;
        /** Share of each thread's time for each move, in {@link Move} order. */
        public double[] shares = {0.3, 0.45, 0.2, 0.05};
        public long seed = 1;
        public int maxWidth = 8000;
        public int maxWindow = 80;
        /**
         * Moves the shares towards the moves that improve the thread's run: every
         * {@link #adaptSeconds}, each move gets {@code base * (0.2 + 0.8 * rate / best rate)}, where
         * the rate is the fitness it gained per second (halved each period, so it follows the run).
         */
        public boolean adaptive = true;
        public double adaptSeconds = 20;
        /** Late acceptance history length. */
        public int history = 200;
        /** Ticks the run-up may grow beyond the longest run-up of the seeds. */
        public int runupSlack = 40;
        /** Steps without improving its current run before a thread restarts from the population. */
        public int patience = 3000;
    }

    /** Counters per move. */
    public static final class Stats {
        public final long[] calls = new long[Move.values().length];
        public final long[] nanos = new long[Move.values().length];
        public final long[] bests = new long[Move.values().length];
    }

    private final Course course;
    private final Options o;
    private final List<Run> pool = new ArrayList<Run>();
    private final List<Run> seeds = new ArrayList<Run>();
    private Run best;
    private final Stats stats = new Stats();
    private Consumer<Run> onBest = r -> { };
    private Move lastBestMove;

    public Improver(Course course, Options o) {
        this.course = course;
        this.o = o;
    }

    public void onBest(Consumer<Run> listener) {
        this.onBest = listener;
    }

    public synchronized Run best() {
        return best;
    }

    public synchronized Stats stats() {
        Stats s = new Stats();
        System.arraycopy(stats.calls, 0, s.calls, 0, s.calls.length);
        System.arraycopy(stats.nanos, 0, s.nanos, 0, s.nanos.length);
        System.arraycopy(stats.bests, 0, s.bests, 0, s.bests.length);
        return s;
    }

    /** Adds a starting run; returns it judged. Invalid runs are not added. */
    public Run seed(Run r) {
        if (r.valid) {
            int limit = r.startTick + o.runupSlack;
            if (course.maxStartTick == Integer.MAX_VALUE || course.maxStartTick < limit) course.maxStartTick = limit;
            synchronized (this) {
                seeds.add(r);
            }
            offer(r, null);
        }
        return r;
    }

    /** Runs the workers until {@code deadlineNanos} ({@code System.nanoTime()}); returns the best run. */
    public Run run(long deadlineNanos) throws InterruptedException {
        if (best() == null) throw new IllegalStateException("no valid starting run");
        Thread[] ts = new Thread[o.threads];
        for (int i = 0; i < ts.length; i++) {
            final long seed = o.seed * 1_000_003L + i;
            final Run home;
            synchronized (this) {
                List<Run> sorted = new ArrayList<Run>(seeds);
                sorted.sort((p, q) -> p.betterThan(q) ? -1 : q.betterThan(p) ? 1 : 0);
                home = sorted.get(i % sorted.size());
            }
            ts[i] = new Thread(() -> work(new Random(seed), home, deadlineNanos), "improve-" + i);
            ts[i].setDaemon(true);
            ts[i].start();
        }
        for (Thread t : ts) t.join();
        if (Rebuild.DEBUG) System.err.println("rebuild stages: " + Rebuild.stages());
        return best();
    }

    /**
     * One worker. Each worker has a home lineage that starts from one of the seeds (island model):
     * when it restarts it goes back to its home run or, half the time, to a population run. Seeds
     * that are worse than the best one so keep being improved on their own, and crossover can join
     * them with the others later.
     */
    private void work(Random rnd, Run home, long deadline) {
        Rebuild rebuild = new Rebuild();
        rebuild.maxWidth = o.maxWidth;
        rebuild.maxWindow = o.maxWindow;
        rebuild.deadlineNanos = deadline;
        YawCma cma = new YawCma();
        Crossover cross = new Crossover();
        double[] spent = new double[Move.values().length];
        double[] share = o.shares.clone();
        double[] gain = new double[share.length], time = new double[share.length];
        long nextAdapt = System.nanoTime() + (long) (o.adaptSeconds * 1e9);
        Run cur = home;
        boolean onHome = true;
        double[] hist = new double[o.history];
        Arrays.fill(hist, cur.fitness());
        long step = 0;
        long sinceImproved = 0;
        Run curBest = cur;
        while (System.nanoTime() < deadline) {
            if (o.adaptive && System.nanoTime() > nextAdapt) {
                adapt(share, gain, time);
                Arrays.fill(spent, 0);
                nextAdapt = System.nanoTime() + (long) (o.adaptSeconds * 1e9);
            }
            Move m = pick(spent, share);
            long t0 = System.nanoTime();
            Run r;
            try {
                switch (m) {
                    case MICRO: r = Micro.mutate(course, cur, rnd); break;
                    case REBUILD: r = rebuild.apply(course, cur, rnd); break;
                    case CMA: r = cma.apply(course, cur, rnd); break;
                    default: r = cross.apply(course, cur, sample(rnd), rnd); break;
                }
            } catch (OutOfMemoryError e) {
                r = null;
            }
            long dt = System.nanoTime() - t0;
            spent[m.ordinal()] += dt / share[m.ordinal()];
            time[m.ordinal()] += dt / 1e9;
            synchronized (this) {
                stats.calls[m.ordinal()]++;
                stats.nanos[m.ordinal()] += dt;
            }
            if (r != null && r.valid) {
                offer(r, m);
                double f = r.fitness();
                if (f < cur.fitness()) gain[m.ordinal()] += cur.fitness() - f;
                int h = (int) (step % hist.length);
                if (f <= cur.fitness() || f <= hist[h]) cur = r;
                hist[h] = cur.fitness();
                step++;
            }
            if (cur.betterThan(curBest)) {
                curBest = cur;
                if (onHome && cur.betterThan(home)) home = cur;
                sinceImproved = 0;
            } else if (++sinceImproved > o.patience) {
                onHome = rnd.nextBoolean();
                cur = onHome ? home : sample(rnd);
                curBest = cur;
                Arrays.fill(hist, cur.fitness());
                sinceImproved = 0;
            }
        }
    }

    /** The move whose time, divided by its share, is smallest. */
    private static Move pick(double[] spent, double[] share) {
        int k = 0;
        for (int i = 1; i < spent.length; i++) if (share[i] > 0 && (share[k] <= 0 || spent[i] < spent[k])) k = i;
        return Move.values()[k];
    }

    /** New shares from the gains per second of each move ({@link Options#adaptive}); halves the counts. */
    private void adapt(double[] share, double[] gain, double[] time) {
        double[] rate = new double[share.length];
        double top = 0;
        for (int i = 0; i < share.length; i++) {
            rate[i] = gain[i] / Math.max(time[i], 1);
            top = Math.max(top, rate[i]);
        }
        for (int i = 0; i < share.length; i++) {
            share[i] = top > 0 ? o.shares[i] * (0.2 + 0.8 * rate[i] / top) : o.shares[i];
            gain[i] /= 2;
            time[i] /= 2;
        }
    }

    /** A population run, better ones more often (rank weights 1, 1/2, 1/3, ...). */
    private synchronized Run sample(Random rnd) {
        double total = 0;
        for (int i = 0; i < pool.size(); i++) total += 1.0 / (i + 1);
        double u = rnd.nextDouble() * total;
        for (int i = 0; i < pool.size(); i++) {
            u -= 1.0 / (i + 1);
            if (u <= 0) return pool.get(i);
        }
        return pool.get(pool.size() - 1);
    }

    private void offer(Run r, Move m) {
        Run newBest = null;
        synchronized (this) {
            for (Run p : pool) {
                if (p.ticks() == r.ticks() && Math.abs(p.slack - r.slack) < 1e-9) return;
            }
            int i = 0;
            while (i < pool.size() && !r.betterThan(pool.get(i))) i++;
            if (i >= o.population) return;
            pool.add(i, r);
            while (pool.size() > o.population) pool.remove(pool.size() - 1);
            if (r.betterThan(best)) {
                boolean newTick = best == null || r.ticks() < best.ticks();
                best = r;
                if (m != null) stats.bests[m.ordinal()]++;
                lastBestMove = m;
                if (newTick) newBest = r;
            }
        }
        if (newBest != null) onBest.accept(newBest);
    }

    public synchronized Move lastBestMove() {
        return lastBestMove;
    }
}
