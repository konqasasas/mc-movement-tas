package io.github.konqasasas.mctas.problem.judge;

import io.github.konqasasas.mctas.physics.BlockWorld;
import io.github.konqasasas.mctas.physics.Physics;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.Vec3;
import io.github.konqasasas.mctas.problem.input.Key;
import io.github.konqasasas.mctas.problem.input.TickInput;

public final class PhysicsModel implements MovementModel<PlayerState> {
    private final BlockWorld world;
    private final boolean hazards;

    public PhysicsModel(Problem problem) {
        this.world = problem.world;
        this.hazards = Hazards.present(problem.world);
    }

    @Override
    public String name() {
        return "physics-1.12.2";
    }

    @Override
    public PlayerState spawn(Problem problem) {
        PlayerState s = PlayerState.standing(problem.spawn.pos.x, problem.spawn.pos.y, problem.spawn.pos.z, problem.spawn.yaw);
        s.rotationPitch = problem.spawn.pitch;
        s.hotbar = problem.inventory;
        s.selectedSlot = problem.selectedSlot;
        return s;
    }

    @Override
    public PlayerState tick(PlayerState state, TickInput input) {
        return Physics.tick(world, state, toPhysics(input));
    }

    @Override
    public String violation(PlayerState before, PlayerState after) {
        return hazards ? Hazards.check(world, before, after) : null;
    }

    @Override
    public Vec3 position(PlayerState state) {
        return new Vec3(state.posX, state.posY, state.posZ);
    }

    public static PlayerInput toPhysics(TickInput in) {
        int k = 0;
        if (in.has(Key.W)) k |= PlayerInput.W;
        if (in.has(Key.A)) k |= PlayerInput.A;
        if (in.has(Key.S)) k |= PlayerInput.S;
        if (in.has(Key.D)) k |= PlayerInput.D;
        if (in.has(Key.JUMP)) k |= PlayerInput.JUMP;
        if (in.has(Key.SPRINT)) k |= PlayerInput.SPRINT;
        if (in.has(Key.SNEAK)) k |= PlayerInput.SNEAK;
        if (in.has(Key.USE)) k |= PlayerInput.USE;
        if (in.has(Key.CLOSE)) k |= PlayerInput.CLOSE;
        return new PlayerInput(k, in.yaw, in.pitch, in.slot);
    }
}
