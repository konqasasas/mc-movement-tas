package io.github.konqasasas.mctas.approx;

import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.io.ProblemIO;

import java.nio.file.Paths;

/** Plays the pilot alone from the spawn and prints where it gets to. Not a test; run by hand. */
public final class PilotCheck {
    public static void main(String[] args) throws Exception {
        for (String f : args) {
            Problem p = ProblemIO.read(Paths.get(f));
            Route route = new Route(p, Double.parseDouble(System.getProperty("slack", "0")));
            Pilot pilot = new Pilot(p, route);
            PlayerState s = Rollout.spawn(p);
            Pilot.Run r = pilot.play(s, 0, 0, -1, false, Integer.MIN_VALUE / 2, Integer.MAX_VALUE, p.maxTicks, true);
            System.out.println(p.id + ": " + (r.finished ? "finished " + (r.ticks - r.entry) : (r.failed ? "FAILED" : "stopped"))
                + " at stage " + r.stage + "/" + (route.waypoints.size() - 1) + " tick " + r.ticks + " " + r.end);
            System.out.println("   route " + route);
            if (args.length == 1) {
                PlayerState x = Rollout.spawn(p);
                int t = 0;
                for (PlayerInput a : r.inputs) {
                    x = io.github.konqasasas.mctas.physics.Physics.tick(p.world, x, a);
                    t++;
                    if (t > r.ticks - Integer.getInteger("last", 40)) System.out.printf("  t=%d %s pos=(%.3f, %.3f, %.3f) v=(%.3f, %.3f, %.3f) g=%b spr=%b%n", t, a, x.posX, x.posY, x.posZ, x.motionX, x.motionY, x.motionZ, x.onGround, x.sprinting);
                }
            }
        }
    }
}
