// Compares two runs of the same problem tick by tick and says where the second one loses time.
// The runs are aligned by position (dynamic time warping over the feet positions from the start
// tick to the goal tick), so each tick of OTHER gets the tick of REF where REF was at the same
// place. The lag is (OTHER ticks since start) - (REF ticks since start at the matched place);
// where it grows, OTHER is slower than REF.
// usage: java RunGap PROBLEM REF.tas|solution.json OTHER.tas|solution.json [--min N] [--csv out.csv] [--json out.json]
import io.github.konqasasas.mctas.physics.*;
import io.github.konqasasas.mctas.problem.*;
import io.github.konqasasas.mctas.problem.io.*;
import io.github.konqasasas.mctas.problem.input.*;
import io.github.konqasasas.mctas.problem.judge.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class RunGap {
  static final class Run {
    final String name; final List<TickInput> in = new ArrayList<>(); final List<PlayerState> st = new ArrayList<>();
    int start, goal;
    Run(String name) { this.name = name; }
    int n() { return goal - start + 1; }
    PlayerState at(int k) { return st.get(start + k); }   // k ticks after the start tick
    TickInput inAt(int k) { return in.get(start + k - 1); } // input that led to at(k)
  }

  static List<TickInput> read(Path p, Problem pr) throws Exception {
    String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    return p.toString().endsWith(".json") ? SolutionIO.readInputs(text) : InputText.parse(text, pr.spawn);
  }

  static Run run(Problem pr, Path p) throws Exception {
    Run r = new Run(p.getFileName().toString());
    r.in.addAll(read(p, pr));
    RunResult rr = Evaluator.run(pr, r.in);
    if (!rr.finished()) throw new IllegalStateException(p + ": " + rr.status);
    PhysicsModel m = new PhysicsModel(pr);
    PlayerState s = m.spawn(pr);
    r.st.add(s.copy());
    for (int i = 0; i < rr.goalTick; i++) { s = m.tick(s, r.in.get(i)); r.st.add(s.copy()); }
    r.start = rr.startTick; r.goal = rr.goalTick;
    return r;
  }

  static double dist(PlayerState a, PlayerState b) {
    double dx = a.posX - b.posX, dy = a.posY - b.posY, dz = a.posZ - b.posZ;
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
  }

  static double hspeed(PlayerState a, PlayerState b) {
    double dx = b.posX - a.posX, dz = b.posZ - a.posZ;
    return Math.sqrt(dx * dx + dz * dz);
  }

  /** match[j] = the first REF tick aligned with OTHER tick j; dev[j] = the distance there. */
  static int[] align(Run a, Run b, double[] dev) {
    int n = a.n(), m = b.n();
    double[][] c = new double[n][m];
    for (int i = 0; i < n; i++) for (int j = 0; j < m; j++) {
      double d = dist(a.at(i), b.at(j));
      double best = i == 0 && j == 0 ? 0 : Double.POSITIVE_INFINITY;
      if (i > 0) best = Math.min(best, c[i - 1][j]);
      if (j > 0) best = Math.min(best, c[i][j - 1]);
      if (i > 0 && j > 0) best = Math.min(best, c[i - 1][j - 1]);
      c[i][j] = best + d;
    }
    int[] match = new int[m];
    Arrays.fill(match, Integer.MAX_VALUE);
    int i = n - 1, j = m - 1;
    while (true) {
      match[j] = Math.min(match[j], i);
      dev[j] = dist(a.at(match[j]), b.at(j));
      if (i == 0 && j == 0) break;
      if (i == 0) j--;
      else if (j == 0) i--;
      else {
        double d = c[i - 1][j - 1], u = c[i - 1][j], l = c[i][j - 1];
        if (d <= u && d <= l) { i--; j--; } else if (u < l) i--; else j--;
      }
    }
    return match;
  }

  static boolean landed(Run r, int k) {
    return k > 0 && r.at(k).onGround && !r.at(k - 1).onGround;
  }

  static String keys(Run r, int k0, int k1) {
    Map<String, Integer> c = new TreeMap<>();
    for (int k = Math.max(1, k0 + 1); k <= k1; k++) {
      StringBuilder s = new StringBuilder();
      for (Key key : r.inAt(k).keys()) { if (key == Key.SPRINT) continue; s.append(key == Key.JUMP ? "J" : key == Key.SNEAK ? "N" : key.name()); }
      c.merge(s.length() == 0 ? "-" : s.toString(), 1, Integer::sum);
    }
    StringBuilder out = new StringBuilder();
    c.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(3)
        .forEach(e -> out.append(out.length() == 0 ? "" : " ").append(e.getKey()).append(':').append(e.getValue()));
    return out.toString();
  }

  static int count(Run r, int k0, int k1, java.util.function.IntPredicate p) {
    int c = 0; for (int k = Math.max(1, k0 + 1); k <= k1; k++) if (p.test(k)) c++; return c;
  }

  static String pos(PlayerState s) { return String.format(Locale.ROOT, "(%.1f,%.1f,%.1f)", s.posX, s.posY, s.posZ); }

  public static void main(String[] args) throws Exception {
    Problem pr = ProblemIO.read(Paths.get(args[0]));
    Run a = run(pr, Paths.get(args[1])), b = run(pr, Paths.get(args[2]));
    int min = 12; Path csv = null, json = null;
    for (int i = 3; i < args.length; i++) {
      if (args[i].equals("--min")) min = Integer.parseInt(args[++i]);
      else if (args[i].equals("--csv")) csv = Paths.get(args[++i]);
      else if (args[i].equals("--json")) json = Paths.get(args[++i]);
    }
    double[] dev = new double[b.n()];
    int[] match = align(a, b, dev);
    System.out.printf(Locale.ROOT, "ref %s: %d ticks (start %d)  other %s: %d ticks (start %d)  diff %+d%n",
        a.name, a.n() - 1, a.start, b.name, b.n() - 1, b.start, (b.n() - a.n()));
    System.out.printf(Locale.ROOT, "start: ref %s v=%.3f  other %s v=%.3f%n", pos(a.at(0)), hspeed(a.st.get(a.start - 1), a.at(0)),
        pos(b.at(0)), hspeed(b.st.get(b.start - 1), b.at(0)));

    // cut OTHER at its landings, at least `min` ticks apart
    List<Integer> cuts = new ArrayList<>();
    cuts.add(0);
    for (int k = 1; k < b.n() - 1; k++) if (landed(b, k) && k - cuts.get(cuts.size() - 1) >= min) cuts.add(k);
    if (b.n() - 1 - cuts.get(cuts.size() - 1) < min / 2 && cuts.size() > 1) cuts.remove(cuts.size() - 1);
    cuts.add(b.n() - 1);

    StringBuilder js = new StringBuilder("{\"problem\":\"" + pr.id + "\",\"ref\":\"" + a.name + "\",\"other\":\"" + b.name
        + "\",\"refTicks\":" + (a.n() - 1) + ",\"otherTicks\":" + (b.n() - 1) + ",\"segments\":[");
    System.out.println("  other ticks |  ref ticks  | loss | lag  | dev  | from -> to            | other: v  sprintOff hitWall keys        | ref: v  sprintOff hitWall keys");
    for (int s = 0; s + 1 < cuts.size(); s++) {
      int k0 = cuts.get(s), k1 = cuts.get(s + 1);
      int r0 = match[k0], r1 = s + 2 == cuts.size() ? a.n() - 1 : match[k1];
      int loss = (k1 - k0) - (r1 - r0);
      double maxDev = 0; for (int k = k0; k <= k1; k++) maxDev = Math.max(maxDev, dev[k]);
      final Run bb = b, aa = a;
      int bOff = count(b, k0, k1, k -> bb.inAt(k).has(Key.W) && !bb.at(k).sprinting && !bb.at(k).isSneaking());
      int aOff = count(a, r0, r1, k -> aa.inAt(k).has(Key.W) && !aa.at(k).sprinting && !aa.at(k).isSneaking());
      int bHit = count(b, k0, k1, k -> bb.at(k).collidedHorizontally);
      int aHit = count(a, r0, r1, k -> aa.at(k).collidedHorizontally);
      double bv = (k1 > k0) ? pathLen(b, k0, k1) / (k1 - k0) : 0, av = (r1 > r0) ? pathLen(a, r0, r1) / (r1 - r0) : 0;
      System.out.printf(Locale.ROOT, "  %4d-%-4d %3d | %4d-%-4d %3d | %+4d | %+4d | %4.1f | %-21s | %.3f %3d %3d %-16s | %.3f %3d %3d %s%n",
          k0, k1, k1 - k0, r0, r1, r1 - r0, loss, k1 - r1, maxDev, pos(b.at(k0)) + "->" + pos(b.at(k1)),
          bv, bOff, bHit, keys(b, k0, k1), av, aOff, aHit, keys(a, r0, r1));
      js.append(s == 0 ? "" : ",").append(String.format(Locale.ROOT,
          "{\"otherFrom\":%d,\"otherTo\":%d,\"refFrom\":%d,\"refTo\":%d,\"loss\":%d,\"maxDev\":%.3f,"
              + "\"from\":[%.3f,%.3f,%.3f],\"to\":[%.3f,%.3f,%.3f],\"otherSprintOff\":%d,\"otherHitWall\":%d,\"refSprintOff\":%d,\"refHitWall\":%d}",
          k0, k1, r0, r1, loss, maxDev, b.at(k0).posX, b.at(k0).posY, b.at(k0).posZ, b.at(k1).posX, b.at(k1).posY, b.at(k1).posZ,
          bOff, bHit, aOff, aHit));
    }
    js.append("]}\n");
    if (json != null) Files.write(json, js.toString().getBytes(StandardCharsets.UTF_8));
    if (csv != null) {
      StringBuilder out = new StringBuilder("run,k,x,y,z,hspeed,ground,sprint,sneak,hitWall,keys,yaw,match,dev\n");
      for (Run r : new Run[] {a, b}) for (int k = 0; k < r.n(); k++) {
        PlayerState p = r.at(k);
        double v = k == 0 ? 0 : hspeed(r.at(k - 1), p);
        out.append(String.format(Locale.ROOT, "%s,%d,%.5f,%.5f,%.5f,%.4f,%d,%d,%d,%d,%s,%.2f,%d,%.3f%n", r == a ? "ref" : "other", k,
            p.posX, p.posY, p.posZ, v, p.onGround ? 1 : 0, p.sprinting ? 1 : 0, p.isSneaking() ? 1 : 0, p.collidedHorizontally ? 1 : 0,
            k == 0 ? "" : keys(r, k - 1, k).replaceAll(":1", ""), k == 0 ? 0f : r.inAt(k).yaw, r == b ? match[k] : k, r == b ? dev[k] : 0));
      }
      Files.write(csv, out.toString().getBytes(StandardCharsets.UTF_8));
    }
  }

  static double pathLen(Run r, int k0, int k1) {
    double s = 0; for (int k = k0 + 1; k <= k1; k++) s += hspeed(r.at(k - 1), r.at(k)); return s;
  }
}
