package io.github.konqasasas.mctas.search.bound;

import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.Region;
import io.github.konqasasas.mctas.problem.io.ProblemIO;
import io.github.konqasasas.mctas.search.exact.ExactModel;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.fail;

/**
 * Plays random inputs on the exact physics and checks that every state lies in the sets of the
 * lower-bound analysis: before the start in the fixed point, and k ticks after any start entry in
 * the set for tick k. A miss means the bound would not be a proof.
 */
public class SoundnessTest {

    private static final String[] PROBLEMS = {"03-gap-2", "05-up-1", "06-down-1-gap-4", "07-side-2", "10-three-jumps-zigzag"};

    @Test
    public void randomRunsStayInsideTheSets() throws IOException {
        for (String id : PROBLEMS) check(id, 300, 25);
    }

    static void check(String id, int runs, int afterStart) throws IOException {
        File f = new File("../problems/v0/" + id + ".problem.json");
        Problem problem = ProblemIO.read(f.toPath());
        LowerBound lb = new LowerBound(problem);
        lb.keepLayers = true;
        lb.ignoreGoal = true;
        lb.cap = 4;
        lb.run(afterStart);
        ExactModel model = new ExactModel(problem);
        Random rnd = new Random(id.hashCode());
        Region st = problem.start;
        int entries = 0;
        for (int r = 0; r < runs; r++) {
            List<PlayerState> path = new ArrayList<PlayerState>();
            PlayerState s = model.spawn(problem);
            path.add(s);
            int keys = PlayerInput.W | PlayerInput.SPRINT;
            float yaw = 0;
            // Run up the corridor first (sprinting, small random turns and jumps), then play random
            // inputs from a random point near the start.
            double free = st.minZ - rnd.nextDouble() * 2.5;
            for (int t = 1; t <= 220; t++) {
                if (s.posZ < free) {
                    keys = PlayerInput.W | PlayerInput.SPRINT;
                    yaw = (float) (rnd.nextGaussian() * 3 + (s.posX - 0.5) * 30);
                } else {
                    if (rnd.nextDouble() < 0.3) keys = randomKeys(rnd);
                    if (rnd.nextDouble() < 0.4) yaw = randomYaw(rnd);
                }
                int k = keys;
                if (rnd.nextDouble() < 0.25) k |= PlayerInput.JUMP;
                s = model.tick(s, PlayerInput.of(k, yaw));
                // The run fails when it falls below the void level or below every block top and both
                // regions; the analysis drops those states.
                if (s.posY < problem.voidY || lb.hopeless(s.posY, s.motionY, s.onGround)) break;
                path.add(s);
            }
            if (System.getenv("MCTAS_DEBUG") != null && r < 10) System.err.println("run " + r + " len " + path.size() + " end " + path.get(path.size() - 1));
            for (int t = 0; t < path.size(); t++) {
                PlayerState p = path.get(t);
                if (!lb.holds(-1, v(p), p.posX, p.posZ, p.motionX, p.motionZ, 1e-7)) {
                    fail(id + " run " + r + " tick " + t + ": state before the start outside the sets: " + p);
                }
                boolean entered = t > 0 && st.contains(p.posX, p.posY, p.posZ) && !st.contains(path.get(t - 1).posX, path.get(t - 1).posY, path.get(t - 1).posZ);
                if (!entered) continue;
                entries++;
                for (int k = 0; k <= afterStart && t + k < path.size() && k < lb.layers.size(); k++) {
                    PlayerState q = path.get(t + k);
                    if (!lb.holds(k, v(q), q.posX, q.posZ, q.motionX, q.motionZ, 1e-7)) {
                        fail(id + " run " + r + " entry at " + t + ", k=" + k + ": state outside the sets: " + q);
                    }
                }
            }
        }
        if (entries < runs / 10) fail(id + ": too few start entries to test (" + entries + ")");
    }

    private static VState v(PlayerState p) {
        return new VState(p.box.minY, p.box.maxY, p.motionY, p.onGround);
    }

    private static int randomKeys(Random rnd) {
        int k = 0;
        if (rnd.nextDouble() < 0.9) k |= PlayerInput.W;
        if (rnd.nextDouble() < 0.8) k |= PlayerInput.SPRINT;
        if (rnd.nextDouble() < 0.25) k |= PlayerInput.A;
        if (rnd.nextDouble() < 0.25) k |= PlayerInput.D;
        if (rnd.nextDouble() < 0.05) k |= PlayerInput.S;
        if (rnd.nextDouble() < 0.08) k |= PlayerInput.SNEAK;
        return k;
    }

    private static float randomYaw(Random rnd) {
        double u = rnd.nextDouble();
        if (u < 0.7) return (float) (rnd.nextGaussian() * 25);
        if (u < 0.9) return (float) (rnd.nextDouble() * 360 - 180);
        return (float) (102942.92 + rnd.nextGaussian() * 1000);
    }
}
