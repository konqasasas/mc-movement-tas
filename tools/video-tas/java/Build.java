// Joins a run-up (mask:yaw ...) and the matched masks (yaw 0), checks the run against the HUD readings,
// judges it, and writes the .tas.
// usage: java Build PROBLEM "RUNUP" "MASKS" OBS.json OBS_TICK_OF_FIRST_MASK OUT.tas
import io.github.konqasasas.mctas.physics.*;
import io.github.konqasasas.mctas.problem.*;
import io.github.konqasasas.mctas.problem.io.*;
import io.github.konqasasas.mctas.problem.input.*;
import io.github.konqasasas.mctas.problem.judge.*;
import java.nio.file.*; import java.util.*;
import com.google.gson.*;
public class Build {
  static TickInput ti(int m,float yaw){ EnumSet<Key> ks=EnumSet.noneOf(Key.class);
    if((m&1)!=0)ks.add(Key.W); if((m&2)!=0)ks.add(Key.A); if((m&4)!=0)ks.add(Key.S); if((m&8)!=0)ks.add(Key.D);
    if((m&16)!=0)ks.add(Key.JUMP); if((m&32)!=0)ks.add(Key.SPRINT); if((m&64)!=0)ks.add(Key.SNEAK);
    return new TickInput(ks,yaw,0f); }
  public static void main(String[] a) throws Exception {
    Problem pr=ProblemIO.read(Paths.get(a[0]));
    String[] runup=a[1].trim().split("\\s+"); String[] human=a[2].trim().split("\\s+");
    JsonObject obs=new JsonParser().parse(new String(Files.readAllBytes(Paths.get(a[3])))).getAsJsonObject();
    int humanT0=Integer.parseInt(a[4]); // obs tick after first human input
    List<TickInput> in=new ArrayList<>();
    for(String r:runup){ String[] p=r.split(":"); in.add(ti(Integer.parseInt(p[0]),Float.parseFloat(p[1]))); }
    int off=in.size();
    for(String h:human) in.add(ti(Integer.parseInt(h),0f));
    // simulate and compare
    PhysicsModel m=new PhysicsModel(pr); PlayerState s=m.spawn(pr); double maxd=0; int at=-1;
    for(int i=0;i<in.size();i++){ s=m.tick(s,in.get(i)); if(i>=off){ JsonArray o=obs.getAsJsonArray(""+(humanT0+i-off)); if(o==null)continue;
      double d=Math.max(Math.max(Math.abs(s.posX-o.get(0).getAsDouble()),Math.abs(s.posY-o.get(1).getAsDouble())),Math.abs(s.posZ-o.get(2).getAsDouble()));
      if(d>maxd){maxd=d;at=humanT0+i-off;} } }
    System.out.println("maxdev "+maxd+" at obs tick "+at);
    RunResult rr=Evaluator.run(pr,in);
    System.out.println("result "+rr.status+" ticks "+rr.ticks()+" start "+rr.startTick+" goal "+rr.goalTick+" toggleViol "+ToggleRule.violations(pr,in));
    Files.write(Paths.get(a[5]), InputText.format(in.subList(0,Math.min(in.size(),rr.goalTick>0?rr.goalTick:in.size())), pr.spawn).getBytes());
  }
}
