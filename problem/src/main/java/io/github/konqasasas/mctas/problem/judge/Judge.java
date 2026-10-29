package io.github.konqasasas.mctas.problem.judge;

import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class Judge {
    private final Problem problem;
    private final List<Vec3> positions = new ArrayList<Vec3>();
    private int lastStartEntry = -1;
    private int goalTick = -1;
    private RunResult.Status terminal;
    private String violation;
    private int violationTick = -1;

    public Judge(Problem problem) {
        this.problem = problem;
        Vec3 p0 = problem.spawn.pos;
        positions.add(p0);
        if (problem.start.contains(p0)) lastStartEntry = 0;
    }

    public boolean observe(Vec3 p) {
        if (done()) throw new IllegalStateException("run already decided");
        int t = positions.size();
        Vec3 prev = positions.get(t - 1);
        positions.add(p);
        if (lastStartEntry >= 0 && problem.goal.contains(p)) {
            goalTick = t;
            terminal = RunResult.Status.FINISHED;
            return true;
        }
        if (problem.start.contains(p) && !problem.start.contains(prev)) {
            lastStartEntry = t;
        }
        if (p.y < problem.voidY) {
            terminal = RunResult.Status.FELL_INTO_VOID;
            return true;
        }
        if (t >= problem.maxTicks) {
            terminal = RunResult.Status.TOO_LONG;
            return true;
        }
        return false;
    }

    /** Ends the run as invalid (D-005) on the tick just observed, even if it reached the goal there. */
    public void reject(String why) {
        violation = why;
        violationTick = positions.size() - 1;
        goalTick = -1;
        terminal = RunResult.Status.HURT;
    }

    public boolean done() {
        return terminal != null;
    }

    public int lastStartEntry() {
        return lastStartEntry;
    }

    public RunResult result() {
        RunResult.Status st = terminal == null ? RunResult.Status.NOT_FINISHED : terminal;
        int start = st == RunResult.Status.FINISHED ? lastStartEntry : -1;
        return new RunResult(st, start, goalTick, new ArrayList<Vec3>(positions), violation, violationTick);
    }
}
