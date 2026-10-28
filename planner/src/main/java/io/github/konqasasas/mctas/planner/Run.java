package io.github.konqasasas.mctas.planner;

import io.github.konqasasas.mctas.physics.Aabb;
import io.github.konqasasas.mctas.physics.BlockWorld;
import io.github.konqasasas.mctas.physics.Physics;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.judge.ToggleRule;

/**
 * A run in progress under the judge's rules: the player state, the clock since the spawn, the last
 * start entry, the last sprint/sneak toggle, and whether the run is dead (void, damage, a broken
 * toggle rule, an action the engine does not model) or has reached the goal.
 *
 * <p>Inputs are not stored here; a plan keeps its own list. Copy a run to branch it.
 */
final class Run {
    final Course course;
    PlayerState s;
    /** Ticks since the spawn. */
    int tick;
    /** Tick of the last start entry, or -1 before the first. */
    int entry = -1;
    boolean inStart;
    int lastToggle = Integer.MIN_VALUE / 2;
    boolean dead;
    /** Tick the goal was first reached after a start entry, or -1. */
    int goalTick = -1;

    Run(Course course, PlayerState s) {
        this.course = course;
        this.s = s;
    }

    static Run spawn(Course course) {
        Problem p = course.problem;
        PlayerState s = PlayerState.standing(p.spawn.pos.x, p.spawn.pos.y, p.spawn.pos.z, p.spawn.yaw);
        s.rotationPitch = p.spawn.pitch;
        s.hotbar = p.inventory;
        s.selectedSlot = p.selectedSlot;
        Run r = new Run(course, s);
        r.inStart = p.start.contains(s.posX, s.posY, s.posZ);
        if (r.inStart) r.entry = 0;
        return r;
    }

    Run copy() {
        Run r = new Run(course, s.copy());
        r.tick = tick;
        r.entry = entry;
        r.inStart = inStart;
        r.lastToggle = lastToggle;
        r.dead = dead;
        r.goalTick = goalTick;
        return r;
    }

    boolean done() {
        return goalTick >= 0;
    }

    boolean over() {
        return dead || goalTick >= 0;
    }

    /** Ticks counted by the judge so far: since the last start entry (0 before it). */
    int clock() {
        return entry < 0 ? 0 : tick - entry;
    }

    /** The time the judge gives, once done. */
    int time() {
        return goalTick - entry;
    }

    /**
     * The input as it will be played: without the sprint key when pressing it now would start a sprint
     * too soon after the last toggle (a wall may have just stopped the sprint).
     */
    PlayerInput legal(PlayerInput a) {
        if (ToggleRule.allowed(lastToggle, tick + 1)) return a;
        int keys = a.keys;
        // Too soon to change sneaking: keep it as it is (sneaking also keeps the sprint off).
        if (a.has(PlayerInput.SNEAK) != s.isSneaking()) keys = s.isSneaking() ? keys | PlayerInput.SNEAK : keys & ~PlayerInput.SNEAK;
        if ((keys & PlayerInput.SPRINT) != 0 && !s.sprinting) keys &= ~PlayerInput.SPRINT;
        return keys == a.keys ? a : new PlayerInput(keys, a.yaw, a.pitch, a.slot);
    }

    /** Plays one tick of {@code a} (made {@link #legal} by the caller) and updates the judge's view. */
    void step(PlayerInput a) {
        boolean sprint = s.sprinting, sneak = s.prevSneak;
        Physics.step(course.world, s, a);
        tick++;
        if (s.sprinting != sprint || s.prevSneak != sneak) {
            if (!ToggleRule.allowed(lastToggle, tick)) dead = true;
            lastToggle = tick;
        }
        if (s.unsupportedAction || s.posY < course.problem.voidY || course.hurts(s)) {
            dead = true;
            return;
        }
        if (entry >= 0 && course.problem.goal.contains(s.posX, s.posY, s.posZ)) {
            goalTick = tick;
            return;
        }
        if (tick >= course.problem.maxTicks) {
            dead = true;
            return;
        }
        boolean in = course.problem.start.contains(s.posX, s.posY, s.posZ);
        if (in && !inStart) entry = tick;
        inStart = in;
    }

    /** The player's box, for tests against the world. */
    Aabb box() {
        return s.box;
    }

    BlockWorld world() {
        return course.world;
    }
}
