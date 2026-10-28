// Finds, tick by tick, the key masks whose next position matches the HUD readings (to tol) at a fixed yaw.
// usage: java Match PROBLEM OBS.json FIRST_TICK LAST_TICK TOL YAW
// The player starts standing at the reading of FIRST_TICK. Prints PATH with one mask per tick
// (bits of PlayerInput: W=1 A=2 S=4 D=8 JUMP=16 SPRINT=32 SNEAK=64).
import io.github.konqasasas.mctas.physics.*;
import io.github.konqasasas.mctas.problem.*;
import io.github.konqasasas.mctas.problem.io.*;
import java.nio.file.*; import java.util.*;
import com.google.gson.*;

public class Match {
  static final class Node { PlayerState s; Node parent; int mask; Node(PlayerState s, Node p, int m){this.s=s;parent=p;mask=m;} }
  public static void main(String[] a) throws Exception {
    Problem pr = ProblemIO.read(Paths.get(a[0]));
    JsonObject obs = new JsonParser().parse(new String(Files.readAllBytes(Paths.get(a[1])))).getAsJsonObject();
    int t0 = Integer.parseInt(a[2]), t1 = Integer.parseInt(a[3]);
    double tol = Double.parseDouble(a[4]);
    float yaw = Float.parseFloat(a[5]);
    JsonArray p0 = obs.getAsJsonArray(""+t0);
    List<Node> beam = new ArrayList<>();
    for (int sp=0; sp<2; sp++){ PlayerState s = PlayerState.standing(p0.get(0).getAsDouble(), p0.get(1).getAsDouble(), p0.get(2).getAsDouble(), yaw); s.sprinting = sp==1; beam.add(new Node(s,null,-1)); }
    BlockWorld w = pr.world;
    for (int t=t0+1; t<=t1; t++) {
      JsonArray o = obs.getAsJsonArray(""+t);
      double ox=o.get(0).getAsDouble(), oy=o.get(1).getAsDouble(), oz=o.get(2).getAsDouble();
      Map<Long,Node> next = new LinkedHashMap<>();
      for (Node n : beam) for (int m=0;m<128;m++) {
        if ((m&PlayerInput.W)!=0 && (m&PlayerInput.S)!=0) continue;
        if ((m&PlayerInput.A)!=0 && (m&PlayerInput.D)!=0) continue;
        PlayerState s = Physics.tick(w, n.s, new PlayerInput(m, yaw, 0f, 0));
        if (Math.abs(s.posX-ox)<tol && Math.abs(s.posY-oy)<tol && Math.abs(s.posZ-oz)<tol) {
          long h = s.contentHash(); if (!next.containsKey(h)) next.put(h, new Node(s,n,m));
        }
      }
      beam = new ArrayList<>(next.values());
      if (beam.size()>3000) beam = beam.subList(0,3000);
      if (beam.isEmpty()) { System.out.println("DEAD at "+t+" obs "+ox+" "+oy+" "+oz);
        // nearest
        return; }
      if (t%10==0) System.out.println(t+" beam "+beam.size());
    }
    Node n = beam.get(0); List<Integer> ms = new ArrayList<>();
    while (n.parent!=null){ ms.add(n.mask); n=n.parent; }
    Collections.reverse(ms);
    StringBuilder sb=new StringBuilder();
    for (int m: ms) sb.append(m).append(' ');
    System.out.println("PATH "+sb);
  }
}
