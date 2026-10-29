// Joins a run-up and the matched ticks (both as mask:yaw), replays them against the per-tick
// readings, judges the run and writes the .tas.
// usage: java Assemble PROBLEM "RUNUP" "PATH" TICKS.json OUT.tas
import io.github.konqasasas.mctas.physics.*;
import io.github.konqasasas.mctas.problem.*;
import io.github.konqasasas.mctas.problem.io.*;
import io.github.konqasasas.mctas.problem.input.*;
import io.github.konqasasas.mctas.problem.judge.*;
import java.nio.file.*; import java.util.*;
import com.google.gson.*;

public class Assemble {
  static TickInput ti(int m, float yaw) {
    EnumSet<Key> ks = EnumSet.noneOf(Key.class);
    if ((m & PlayerInput.W) != 0) ks.add(Key.W); if ((m & PlayerInput.A) != 0) ks.add(Key.A);
    if ((m & PlayerInput.S) != 0) ks.add(Key.S); if ((m & PlayerInput.D) != 0) ks.add(Key.D);
    if ((m & PlayerInput.JUMP) != 0) ks.add(Key.JUMP); if ((m & PlayerInput.SPRINT) != 0) ks.add(Key.SPRINT);
    if ((m & PlayerInput.SNEAK) != 0) ks.add(Key.SNEAK);
    return new TickInput(ks, yaw, 0f);
  }

  static void add(List<TickInput> in, String seq) {
    for (String r : seq.trim().split("\\s+")) { String[] p = r.split(":"); in.add(ti(Integer.parseInt(p[0]), Float.parseFloat(p[1]))); }
  }

  public static void main(String[] a) throws Exception {
    Problem pr = ProblemIO.read(Paths.get(a[0]));
    List<TickInput> in = new ArrayList<>();
    add(in, a[1]);
    int off = in.size();
    add(in, a[2]);
    JsonArray ticks = new JsonParser().parse(new String(Files.readAllBytes(Paths.get(a[3])))).getAsJsonArray();
    PhysicsModel m = new PhysicsModel(pr); PlayerState s = m.spawn(pr); double maxd = 0; int at = -1;
    for (int i = 0; i < in.size(); i++) {
      s = m.tick(s, in.get(i));
      if (i + 1 >= off) {
        JsonArray o = ticks.get(i + 1 - off).getAsJsonObject().getAsJsonArray("pos");
        double d = Math.max(Math.abs(s.posX - o.get(0).getAsDouble()), Math.max(Math.abs(s.posY - o.get(1).getAsDouble()), Math.abs(s.posZ - o.get(2).getAsDouble())));
        if (d > maxd) { maxd = d; at = i + 1 - off; }
      }
    }
    System.out.println("max deviation from the readings " + maxd + " at reading " + at);
    RunResult rr = Evaluator.run(pr, in);
    System.out.println("result " + rr.status + " ticks " + rr.ticks() + " start " + rr.startTick + " goal " + rr.goalTick
        + " toggle violations " + ToggleRule.violations(pr, in));
    Files.write(Paths.get(a[4]), InputText.format(in.subList(0, rr.goalTick > 0 ? rr.goalTick : in.size()), pr.spawn).getBytes());
  }
}
