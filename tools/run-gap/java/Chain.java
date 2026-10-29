// Re-searches a whole run leg by leg along its own landings, to see whether the local slack that
// single-segment probes find adds up over the course. The run is cut at its landings at least MIN
// ticks apart; each leg is a technique SegmentSearch from the current state to a landing at the same
// height within R blocks of the run's landing point, and the last leg goes to the goal box. Up to
// KEEP partial runs (fewest ticks, then fastest along the next heading) carry over between legs.
// usage: java Chain PROBLEM RUN.tas OUT.tas [--min N] [--width N] [--keep K] [--radius R] [--max T]
import io.github.konqasasas.mctas.physics.*;
import io.github.konqasasas.mctas.problem.*;
import io.github.konqasasas.mctas.problem.io.*;
import io.github.konqasasas.mctas.problem.input.*;
import io.github.konqasasas.mctas.problem.judge.*;
import io.github.konqasasas.mctas.technique.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class Chain {
  static final class Part {
    final List<PlayerInput> in; final PlayerState s; final int lastToggle; // lastToggle relative to s
    Part(List<PlayerInput> in, PlayerState s, int lastToggle) { this.in = in; this.s = s; this.lastToggle = lastToggle; }
  }

  static float heading(double x0, double z0, double x1, double z1) {
    return (float) Math.toDegrees(Math.atan2(-(x1 - x0), z1 - z0));
  }

  static Exit landingNear(double x, double z, double topY, double r) {
    return Exit.landing(new io.github.konqasasas.mctas.technique.Target(x - r, x + r, z - r, z + r, topY));
  }

  public static void main(String[] a) throws Exception {
    Problem p = ProblemIO.read(Paths.get(a[0]));
    String text = new String(Files.readAllBytes(Paths.get(a[1])), StandardCharsets.UTF_8);
    List<TickInput> run = a[1].endsWith(".json") ? SolutionIO.readInputs(text) : InputText.parse(text, p.spawn);
    int min = 20, keep = 2; double radius = 1.5;
    SegmentSearch.Options o = new SegmentSearch.Options();
    o.width = 20000; o.maxTicks = 80; o.voidY = p.voidY;
    for (int i = 3; i + 1 < a.length; i += 2) {
      switch (a[i]) {
        case "--min": min = Integer.parseInt(a[i + 1]); break;
        case "--width": o.width = Integer.parseInt(a[i + 1]); break;
        case "--keep": keep = Integer.parseInt(a[i + 1]); break;
        case "--radius": radius = Double.parseDouble(a[i + 1]); break;
        case "--max": o.maxTicks = Integer.parseInt(a[i + 1]); break;
        default: throw new IllegalArgumentException(a[i]);
      }
    }
    RunResult rr = Evaluator.run(p, run);
    PhysicsModel m = new PhysicsModel(p);
    List<PlayerState> st = new ArrayList<>();
    PlayerState s = m.spawn(p);
    st.add(s);
    int lastToggle = Integer.MIN_VALUE / 2;
    for (int i = 0; i < rr.goalTick; i++) {
      PlayerState ns = m.tick(s, run.get(i));
      if (i < rr.startTick && (ns.sprinting != s.sprinting || ns.prevSneak != s.prevSneak)) lastToggle = i + 1;
      s = ns; st.add(s);
    }
    List<Integer> cuts = new ArrayList<>();
    cuts.add(rr.startTick);
    for (int t = rr.startTick + 1; t < rr.goalTick; t++)
      if (st.get(t).onGround && !st.get(t - 1).onGround && t - cuts.get(cuts.size() - 1) >= min) cuts.add(t);
    if (rr.goalTick - cuts.get(cuts.size() - 1) < min / 2 && cuts.size() > 1) cuts.remove(cuts.size() - 1);
    System.out.println("run " + rr.ticks() + " ticks, " + cuts.size() + " legs");

    List<TickInput> prefix = new ArrayList<>(run.subList(0, rr.startTick));
    List<Part> parts = new ArrayList<>();
    parts.add(new Part(new ArrayList<>(), st.get(rr.startTick), lastToggle - rr.startTick));
    double gx = (p.goal.minX + p.goal.maxX) / 2, gz = (p.goal.minZ + p.goal.maxZ) / 2;
    for (int leg = 0; leg < cuts.size(); leg++) {
      boolean last = leg + 1 == cuts.size();
      PlayerState to = last ? null : st.get(cuts.get(leg + 1));
      Exit exit = last ? Exit.box(p.goal.minX, p.goal.minY, p.goal.minZ, p.goal.maxX, p.goal.maxY, p.goal.maxZ)
          : landingNear(to.posX, to.posZ, to.posY, radius);
      int runTicks = (last ? rr.goalTick : cuts.get(leg + 1)) - rr.startTick;
      List<Part> next = new ArrayList<>();
      long t0 = System.nanoTime();
      for (Part part : parts) {
        float h = last ? heading(part.s.posX, part.s.posZ, gx, gz) : heading(part.s.posX, part.s.posZ, to.posX, to.posZ);
        List<SegmentSearch.Result> rs = new SegmentSearch().search(p.world, part.s, part.lastToggle, exit, h, o);
        for (SegmentSearch.Result r : rs) {
          List<PlayerInput> in = new ArrayList<>(part.in);
          in.addAll(r.inputs);
          next.add(new Part(in, r.end, r.lastToggle - r.ticks()));
        }
      }
      if (next.isEmpty()) { System.out.println("leg " + leg + ": no way, stop"); return; }
      final PlayerState aim = last ? null : (leg + 2 < cuts.size() ? st.get(cuts.get(leg + 2)) : null);
      next.sort((x, y) -> {
        if (x.in.size() != y.in.size()) return x.in.size() - y.in.size();
        return Double.compare(speedTo(y.s, aim, gx, gz), speedTo(x.s, aim, gx, gz));
      });
      List<Part> kept = new ArrayList<>();
      for (Part x : next) {
        boolean dup = false;
        for (Part k : kept) if (k.in.size() == x.in.size() && Math.abs(k.s.posX - x.s.posX) < 0.05 && Math.abs(k.s.posZ - x.s.posZ) < 0.05) dup = true;
        if (!dup) kept.add(x);
        if (kept.size() >= keep) break;
      }
      parts = kept;
      System.out.printf(Locale.ROOT, "leg %2d: run reaches it at %4d, chain %4d (%+d), exit speed %.3f, %.1f s%n", leg, runTicks,
          parts.get(0).in.size(), parts.get(0).in.size() - runTicks, speedTo(parts.get(0).s, aim, gx, gz), (System.nanoTime() - t0) / 1e9);
    }
    List<TickInput> all = new ArrayList<>(prefix);
    for (PlayerInput x : parts.get(0).in) all.add(toTick(x));
    RunResult r2 = Evaluator.run(p, all);
    System.out.println("chain: " + r2.status + " " + r2.ticks() + " ticks (run " + rr.ticks() + "), toggle violations "
        + ToggleRule.violations(p, all));
    Files.write(Paths.get(a[2]), InputText.format(all.subList(0, r2.finished() ? r2.goalTick : all.size()), p.spawn)
        .getBytes(StandardCharsets.UTF_8));
  }

  static double speedTo(PlayerState s, PlayerState aim, double gx, double gz) {
    double tx = aim == null ? gx : aim.posX, tz = aim == null ? gz : aim.posZ;
    double dx = tx - s.posX, dz = tz - s.posZ, d = Math.hypot(dx, dz);
    return d < 1e-9 ? 0 : (s.motionX * dx + s.motionZ * dz) / d;
  }

  static TickInput toTick(PlayerInput a) {
    EnumSet<Key> k = EnumSet.noneOf(Key.class);
    if (a.has(PlayerInput.W)) k.add(Key.W);
    if (a.has(PlayerInput.A)) k.add(Key.A);
    if (a.has(PlayerInput.S)) k.add(Key.S);
    if (a.has(PlayerInput.D)) k.add(Key.D);
    if (a.has(PlayerInput.JUMP)) k.add(Key.JUMP);
    if (a.has(PlayerInput.SPRINT)) k.add(Key.SPRINT);
    if (a.has(PlayerInput.SNEAK)) k.add(Key.SNEAK);
    return new TickInput(k, a.yaw, a.pitch);
  }
}
