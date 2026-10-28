package io.github.konqasasas.mctas.approx;

import io.github.konqasasas.mctas.physics.Physics;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.input.TickInput;
import io.github.konqasasas.mctas.problem.io.InputText;
import io.github.konqasasas.mctas.problem.io.ProblemIO;
import io.github.konqasasas.mctas.problem.judge.PhysicsModel;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/** Prints the beam's estimate along a known input list. Not a test; run by hand. */
public final class DebugTrace {
    public static void main(String[] args) throws Exception {
        Problem p = ProblemIO.read(Paths.get(args[0]));
        List<TickInput> in = InputText.parse(new String(Files.readAllBytes(Paths.get(args[1])), "UTF-8"), p.spawn);
        Route route = new Route(p);
        System.out.println("route " + route + " floor " + route.floorY);
        TickBeam beam = new TickBeam(p, route, 10);
        PlayerState s = Rollout.spawn(p);
        TickBeam.Node n = new TickBeam.Node(null, null, s, -1, false, 0, -100);
        for (int i = 0; i < in.size(); i++) {
            PlayerInput a = PhysicsModel.toPhysics(in.get(i));
            n = beam.childForDebug(n, a, i + 1);
            if (n == null) { System.out.println("illegal at " + (i + 1)); return; }
            if (i + 1 > in.size() - 25) System.out.printf("t=%d entry=%d stage=%d est=%.2f doomed=%b %s%n", i + 1, n.entry, n.stage, beam.estimate(n), beam.doomedForDebug(n), n.state);
        }
    }
}
