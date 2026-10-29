package io.github.konqasasas.mctas.problem.judge;

import io.github.konqasasas.mctas.problem.Vec3;

import java.util.List;

public final class RunResult {
    /** HURT: the run took damage or trampled farmland (D-005), on the tick {@link #violationTick}. */
    public enum Status { FINISHED, NOT_FINISHED, FELL_INTO_VOID, TOO_LONG, HURT }

    public final Status status;
    public final int startTick;
    public final int goalTick;
    public final List<Vec3> positions;
    /** For HURT: what happened ("damage" or "trampling") and on which tick; otherwise null and -1. */
    public final String violation;
    public final int violationTick;

    RunResult(Status status, int startTick, int goalTick, List<Vec3> positions) {
        this(status, startTick, goalTick, positions, null, -1);
    }

    RunResult(Status status, int startTick, int goalTick, List<Vec3> positions, String violation, int violationTick) {
        this.status = status;
        this.startTick = startTick;
        this.goalTick = goalTick;
        this.positions = positions;
        this.violation = violation;
        this.violationTick = violationTick;
    }

    public boolean finished() {
        return status == Status.FINISHED;
    }

    public int ticks() {
        if (!finished()) throw new IllegalStateException("run did not finish: " + status);
        return goalTick - startTick;
    }

    public int inputsUsed() {
        return finished() ? goalTick : positions.size() - 1;
    }
}
