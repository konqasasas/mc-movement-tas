package io.github.konqasasas.mctas.search.exact;

import io.github.konqasasas.mctas.physics.Physics;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.Vec3;
import io.github.konqasasas.mctas.problem.input.Key;
import io.github.konqasasas.mctas.problem.input.TickInput;
import io.github.konqasasas.mctas.problem.judge.MovementModel;
import io.github.konqasasas.mctas.problem.judge.PhysicsModel;

import java.util.EnumSet;
import java.util.Set;

/** The problem's world and spawn on the bit-exact {@code physics} engine. */
public final class ExactModel implements MovementModel<PlayerState> {

    final SparseBlockWorld world;

    public ExactModel(Problem problem) {
        this.world = problem.world;
    }

    @Override
    public String name() {
        return "physics-1.12.2";
    }

    @Override
    public PlayerState spawn(Problem problem) {
        Vec3 p = problem.spawn.pos;
        PlayerState s = PlayerState.standing(p.x, p.y, p.z, problem.spawn.yaw);
        s.rotationPitch = problem.spawn.pitch;
        s.hotbar = problem.inventory;
        s.selectedSlot = problem.selectedSlot;
        return s;
    }

    @Override
    public PlayerState tick(PlayerState state, TickInput input) {
        return Physics.tick(world, state, toPhysics(input));
    }

    public PlayerState tick(PlayerState state, PlayerInput input) {
        return Physics.tick(world, state, input);
    }

    @Override
    public Vec3 position(PlayerState s) {
        return new Vec3(s.posX, s.posY, s.posZ);
    }

    public static PlayerInput toPhysics(TickInput in) {
        return PhysicsModel.toPhysics(in);
    }

    public static TickInput fromPhysics(PlayerInput in) {
        Set<Key> k = EnumSet.noneOf(Key.class);
        if (in.has(PlayerInput.W)) k.add(Key.W);
        if (in.has(PlayerInput.A)) k.add(Key.A);
        if (in.has(PlayerInput.S)) k.add(Key.S);
        if (in.has(PlayerInput.D)) k.add(Key.D);
        if (in.has(PlayerInput.JUMP)) k.add(Key.JUMP);
        if (in.has(PlayerInput.SPRINT)) k.add(Key.SPRINT);
        if (in.has(PlayerInput.SNEAK)) k.add(Key.SNEAK);
        if (in.has(PlayerInput.USE)) k.add(Key.USE);
        if (in.has(PlayerInput.CLOSE)) k.add(Key.CLOSE);
        return new TickInput(k, in.yaw, in.pitch, in.slot);
    }
}
