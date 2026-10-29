// Like Match, for a HUD that shows the yaw but no tick counter: each tick is one change of the
// displayed position (ticks.json from hud_ocr720.py, one entry per tick with the yaws shown on the
// frames around it). For each tick, tries every key mask at each shown yaw, and if none matches,
// searches the yaw between the shown ones (the tick can use a yaw that no frame showed).
// usage: java YawMatch PROBLEM TICKS.json FIRST LAST TOL
// The player starts standing at entry FIRST. Prints PATH with one mask:yaw per tick.
import io.github.konqasasas.mctas.physics.*;
import io.github.konqasasas.mctas.problem.*;
import io.github.konqasasas.mctas.problem.io.*;
import java.nio.file.*; import java.util.*;
import com.google.gson.*;

public class YawMatch {
  static final class Node { PlayerState s; Node parent; int mask; float yaw; Node(PlayerState s, Node p, int m, float y){this.s=s;parent=p;mask=m;yaw=y;} }
  static BlockWorld w; static double tol;
  /** Whole turns the HUD wraps away: the game's yaw is the shown one plus 360 times this. */
  static int TURNS = Integer.parseInt(System.getProperty("turns", "0"));

  static double err(PlayerState s, double ox, double oy, double oz) {
    return Math.max(Math.abs(s.posY - oy), Math.max(Math.abs(s.posX - ox), Math.abs(s.posZ - oz)));
  }

  /** The state's hash without its yaw: the next tick sets the yaw from the input anyway. */
  static long key(PlayerState s) { PlayerState c = s.copy(); c.rotationYaw = 0; return c.contentHash(); }

  public static void main(String[] a) throws Exception {
    Problem pr = ProblemIO.read(Paths.get(a[0]));
    w = pr.world;
    JsonArray ticks = new JsonParser().parse(new String(Files.readAllBytes(Paths.get(a[1])))).getAsJsonArray();
    int t0 = Integer.parseInt(a[2]), t1 = Integer.parseInt(a[3]);
    tol = Double.parseDouble(a[4]);
    JsonObject e0 = ticks.get(t0).getAsJsonObject();
    JsonArray p0 = e0.getAsJsonArray("pos");
    float yaw0 = e0.getAsJsonArray("yaws").get(0).getAsFloat();
    List<Node> beam = new ArrayList<>();
    for (int sp = 0; sp < 2; sp++) {
      PlayerState s = PlayerState.standing(p0.get(0).getAsDouble(), p0.get(1).getAsDouble(), p0.get(2).getAsDouble(), yaw0);
      s.sprinting = sp == 1; beam.add(new Node(s, null, -1, yaw0));
    }
    int searched = 0;
    for (int t = t0 + 1; t <= t1; t++) {
      JsonObject e = ticks.get(t).getAsJsonObject();
      JsonArray o = e.getAsJsonArray("pos");
      double ox = o.get(0).getAsDouble(), oy = o.get(1).getAsDouble(), oz = o.get(2).getAsDouble();
      List<Float> yaws = new ArrayList<>();
      for (JsonElement y : e.getAsJsonArray("yaws")) yaws.add((float) (y.getAsDouble() + TURNS * 360.0));
      Map<Long, Node> next = new LinkedHashMap<>();
      for (Node n : beam) for (int m = 0; m < 128; m++) {
        if ((m & PlayerInput.W) != 0 && (m & PlayerInput.S) != 0) continue;
        if ((m & PlayerInput.A) != 0 && (m & PlayerInput.D) != 0) continue;
        for (float y : yaws) {
          PlayerState s = Physics.tick(w, n.s, new PlayerInput(m, y, 0f, 0));
          if (err(s, ox, oy, oz) < tol) { long h = key(s); if (!next.containsKey(h)) next.put(h, new Node(s, n, m, y)); }
        }
      }
      if (next.isEmpty()) {
        // The tick's yaw was between the frames. Without collisions the move is base + sin(yaw) u +
        // cos(yaw) v (moveRelative and the sprint-jump boost both turn with the yaw), so three ticks
        // give the yaw in closed form; the sine-table steps around it are then checked exactly.
        searched++; if (System.getenv("YM_DEBUG") != null) System.out.println("tick " + t + " needs a yaw search, yaws " + yaws);
        double mean = 0; for (float y : yaws) mean += y; mean /= yaws.size();
        for (Node n : beam) for (int m = 0; m < 128; m++) {
          if ((m & PlayerInput.W) != 0 && (m & PlayerInput.S) != 0) continue;
          if ((m & PlayerInput.A) != 0 && (m & PlayerInput.D) != 0) continue;
          if ((m & (PlayerInput.W | PlayerInput.A | PlayerInput.S | PlayerInput.D | PlayerInput.JUMP)) == 0) continue;
          PlayerState s0 = Physics.tick(w, n.s, new PlayerInput(m, 0f, 0f, 0));
          PlayerState s90 = Physics.tick(w, n.s, new PlayerInput(m, 90f, 0f, 0));
          PlayerState s180 = Physics.tick(w, n.s, new PlayerInput(m, 180f, 0f, 0));
          if (Math.abs(s0.posY - oy) > 0.01) continue;
          double bx = (s0.posX + s180.posX) / 2, bz = (s0.posZ + s180.posZ) / 2;
          double vx = (s0.posX - s180.posX) / 2, vz = (s0.posZ - s180.posZ) / 2;
          double ux = s90.posX - bx, uz = s90.posZ - bz;
          double rx = ox - bx, rz = oz - bz, det = ux * vz - uz * vx;
          if (Math.abs(det) < 1e-9) continue;
          double sn = (rx * vz - rz * vx) / det, cs = (ux * rz - uz * rx) / det;
          if (Math.abs(Math.hypot(sn, cs) - 1) > 0.05) continue;
          double th = Math.toDegrees(Math.atan2(sn, cs));
          th += 360 * Math.round((mean - th) / 360);
          for (int j = -10; j <= 10; j++) {
            float y = (float) (th + j * (360.0 / 65536.0));
            PlayerState s = Physics.tick(w, n.s, new PlayerInput(m, y, 0f, 0));
            if (err(s, ox, oy, oz) < tol) { long h = key(s); if (!next.containsKey(h)) next.put(h, new Node(s, n, m, y)); }
          }
        }
      }
      if (next.isEmpty()) {
        // A collision breaks the closed form (one axis stops at the wall): scan the yaw instead.
        float lo = Collections.min(yaws) - 8, hi = Collections.max(yaws) + 8;
        for (Node n : beam.subList(0, Math.min(30, beam.size()))) for (int m = 0; m < 128; m++) {
          if ((m & PlayerInput.W) != 0 && (m & PlayerInput.S) != 0) continue;
          if ((m & PlayerInput.A) != 0 && (m & PlayerInput.D) != 0) continue;
          if ((m & (PlayerInput.W | PlayerInput.A | PlayerInput.S | PlayerInput.D | PlayerInput.JUMP)) == 0) continue;
          for (float y = lo; y <= hi; y += 360f / 65536f) {
            PlayerState s = Physics.tick(w, n.s, new PlayerInput(m, y, 0f, 0));
            if (err(s, ox, oy, oz) < tol) { long h = key(s); if (!next.containsKey(h)) next.put(h, new Node(s, n, m, y)); }
          }
        }
      }
      List<Node> beamPrev = beam;
      beam = new ArrayList<>(next.values());
      final double fx = ox, fy = oy, fz = oz;
      beam.sort(Comparator.comparingDouble(q -> err(q.s, fx, fy, fz)));
      if (beam.size() > 1000) beam = beam.subList(0, 1000);
      if (beam.isEmpty()) {
        System.out.println("DEAD at " + t + " obs " + ox + " " + oy + " " + oz + " yaws " + yaws);
        return; }
      if (t % 50 == 0) System.out.println(t + " beam " + beam.size() + " yaw-searched " + searched);
    }
    Node n = beam.get(0); List<String> ms = new ArrayList<>();
    while (n.parent != null) { ms.add(n.mask + ":" + n.yaw); n = n.parent; }
    Collections.reverse(ms);
    System.out.println("yaw-searched ticks " + searched);
    System.out.println("PATH " + String.join(" ", ms));
  }
}
