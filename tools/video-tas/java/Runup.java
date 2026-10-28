// Finds a run-up from the problem spawn that stops at (TX, TZ): k ticks of S (or S+sneak) at one yaw,
// one more tap at another yaw, then standing until the motion is exactly zero.
// usage: java Runup PROBLEM TX TZ
import io.github.konqasasas.mctas.physics.*;
import io.github.konqasasas.mctas.problem.*;
import io.github.konqasasas.mctas.problem.io.*;
import io.github.konqasasas.mctas.problem.judge.*;
import java.nio.file.*; import java.util.*;

public class Runup {
  static BlockWorld w; static PlayerState s0;
  static double TX=8.9375, TZ=22.12417088;
  static PlayerState run(PlayerState s, int mask, float yaw, int n, List<int[]> log, List<Float> ylog){
    for(int i=0;i<n;i++){ s=Physics.tick(w,s,new PlayerInput(mask,yaw,0f,0)); if(log!=null){log.add(new int[]{mask});ylog.add(yaw);} }
    return s;
  }
  static PlayerState settle(PlayerState s, List<int[]> log, List<Float> ylog, float yaw){
    for(int i=0;i<60;i++){ if(s.motionX==0&&s.motionZ==0&&s.onGround&&i>2) break; s=Physics.tick(w,s,new PlayerInput(0,yaw,0f,0)); if(log!=null){log.add(new int[]{0});ylog.add(yaw);} }
    return s;
  }
  static double[] resA(int k,int maskA,float a){ PlayerState s=run(s0,maskA,a,k,null,null); s=settle(s,null,null,a); return new double[]{TX-s.posX,TZ-s.posZ}; }
  static double[] stepB(int maskB,float b,PlayerState from){ PlayerState s=run(from,maskB,b,1,null,null); s=settle(s,null,null,b); return new double[]{s.posX-from.posX,s.posZ-from.posZ}; }
  public static void main(String[] args) throws Exception {
    Problem pr=ProblemIO.read(Paths.get(args[0])); w=pr.world; if(args.length>2){ TX=Double.parseDouble(args[1]); TZ=Double.parseDouble(args[2]); }
    s0=new PhysicsModel(pr).spawn(pr);
    int S=PlayerInput.S, SN=PlayerInput.SNEAK;
    // magnitude of a single sneak-S tap from rest
    PlayerState rest=s0;
    double best=1e9; String bestDesc=null;
    for(int maskB : new int[]{S|SN, S}){
      double[] d=stepB(maskB,0f,rest); double mB=Math.hypot(d[0],d[1]);
      for(int maskA : new int[]{S, S|SN}) for(int k=1;k<=12;k++){
        // scan a in yaw degrees for |resA| - mB sign change
        double prevF=Double.NaN; float prevA=0;
        for(int ai=0; ai<=3600; ai++){
          float a=(float)(ai*0.1-180);
          double[] r=resA(k,maskA,a); double f=Math.hypot(r[0],r[1])-mB;
          if(!Double.isNaN(prevF) && Math.signum(f)!=Math.signum(prevF)){
            // bisection in float
            float lo=prevA, hi=a; double flo=prevF;
            for(int it=0;it<60;it++){ float mid=(lo+hi)/2; if(mid==lo||mid==hi)break; double[] rm=resA(k,maskA,mid); double fm=Math.hypot(rm[0],rm[1])-mB; if(Math.signum(fm)==Math.signum(flo)){lo=mid;flo=fm;}else hi=mid; }
            for(float aa: new float[]{lo,hi}){
              List<int[]> log=new ArrayList<>(); List<Float> yl=new ArrayList<>();
              PlayerState s=run(s0,maskA,aa,k,log,yl); s=settle(s,log,yl,aa);
              double rx=TX-s.posX, rz=TZ-s.posZ;
              // direction for S: moves (sin yaw, -cos yaw)*... find b by scanning fine around atan2
              double bdeg=Math.toDegrees(Math.atan2(rx,-rz));
              float bb=(float)bdeg; double bestErr=1e9; float bestB=bb;
              for(int it=0; it<4000; it++){ float cand=bb+(float)((it-2000)*1e-3); double[] st=stepB(maskB,cand,s); double e=Math.hypot(s.posX+st[0]-TX,s.posZ+st[1]-TZ); if(e<bestErr){bestErr=e;bestB=cand;} }
              // ulp refine
              for(int rep=0;rep<3;rep++) for(int u=-200;u<=200;u++){ float cand=bestB+u*Math.ulp(bestB); double[] st=stepB(maskB,cand,s); double e=Math.hypot(s.posX+st[0]-TX,s.posZ+st[1]-TZ); if(e<bestErr){bestErr=e;bestB=cand;} }
              PlayerState fin=run(s,maskB,bestB,1,log,yl); fin=settle(fin,log,yl,bestB);
              double err=Math.max(Math.abs(fin.posX-TX),Math.abs(fin.posZ-TZ));
              if(err<best){best=err; StringBuilder sb=new StringBuilder();
                for(int i=0;i<log.size();i++) sb.append(log.get(i)[0]).append(':').append(yl.get(i)).append(' ');
                bestDesc="k="+k+" maskA="+maskA+" maskB="+maskB+" err="+err+" x="+fin.posX+" z="+fin.posZ+" y="+fin.posY+"\nRUNUP "+sb;
                System.out.println(bestDesc);}
            }
          }
          prevF=f; prevA=a;
        }
      }
    }
    System.out.println("BEST "+bestDesc);
  }
}
