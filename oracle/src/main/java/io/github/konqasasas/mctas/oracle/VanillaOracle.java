package io.github.konqasasas.mctas.oracle;

import io.github.konqasasas.mctas.physics.Aabb;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.AxisAlignedBB;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs player ticks on the game's own 1.12.2 code, for differential tests of
 * {@link io.github.konqasasas.mctas.physics.Physics}.
 *
 * <p>Needs the 1.12.2 server jar remapped to MCP names on the classpath; see the oracle README.
 */
public final class VanillaOracle {

    private static final Field JUMP_TICKS;

    static {
        Bootstrap.register();
        try {
            JUMP_TICKS = EntityLivingBase.class.getDeclaredField("jumpTicks");
            JUMP_TICKS.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final OracleWorld world;
    private final OraclePlayer player;

    public VanillaOracle(SparseBlockWorld blocks) {
        this.world = new OracleWorld(blocks);
        this.player = new OraclePlayer(world);
    }

    /** Puts the vanilla player into {@code s}. Status effects are not supported yet. */
    public void load(PlayerState s) {
        if (s.speedAmplifier >= 0 || s.slownessAmplifier >= 0 || s.jumpBoostAmplifier >= 0) {
            throw new UnsupportedOperationException("status effects are not wired into the oracle yet");
        }
        OraclePlayer p = player;
        p.width = s.width;
        p.height = s.height;
        p.posX = s.posX;
        p.posY = s.posY;
        p.posZ = s.posZ;
        Aabb b = s.box;
        p.setEntityBoundingBox(new AxisAlignedBB(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ));
        p.motionX = s.motionX;
        p.motionY = s.motionY;
        p.motionZ = s.motionZ;
        p.rotationYaw = s.rotationYaw;
        p.rotationPitch = s.rotationPitch;
        p.onGround = s.onGround;
        p.collidedHorizontally = s.collidedHorizontally;
        p.collidedVertically = s.collidedVertically;
        p.fallDistance = s.fallDistance;
        p.setInWebFlag(s.inWeb);
        p.setInWaterFlag(s.inWater);
        p.setSprinting(s.sprinting);
        setJumpTicks(s.jumpTicks);
        p.jumpMovementFactor = s.jumpMovementFactor;
        p.sprintToggleTimer = s.sprintToggleTimer;
        p.miForward = s.prevMoveForward;
        p.miStrafe = s.prevMoveStrafe;
        p.miJump = s.prevJump;
        p.miSneak = s.prevSneak;
    }

    /** The vanilla player's current state. */
    public PlayerState snapshot() {
        OraclePlayer p = player;
        PlayerState s = new PlayerState();
        s.width = p.width;
        s.height = p.height;
        s.posX = p.posX;
        s.posY = p.posY;
        s.posZ = p.posZ;
        AxisAlignedBB b = p.getEntityBoundingBox();
        s.box = new Aabb(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
        s.motionX = p.motionX;
        s.motionY = p.motionY;
        s.motionZ = p.motionZ;
        s.rotationYaw = p.rotationYaw;
        s.rotationPitch = p.rotationPitch;
        s.onGround = p.onGround;
        s.collidedHorizontally = p.collidedHorizontally;
        s.collidedVertically = p.collidedVertically;
        s.fallDistance = p.fallDistance;
        s.inWeb = p.inWebFlag();
        s.inWater = p.inWaterFlag();
        s.sprinting = p.isSprinting();
        s.jumpTicks = jumpTicks();
        s.jumpMovementFactor = p.jumpMovementFactor;
        s.sprintToggleTimer = p.sprintToggleTimer;
        s.prevMoveForward = p.miForward;
        s.prevMoveStrafe = p.miStrafe;
        s.prevJump = p.miJump;
        s.prevSneak = p.miSneak;
        return s;
    }

    /** One client tick: the view turns to the input's angles, then the player updates. */
    public PlayerState tick(PlayerInput input) {
        player.input = input;
        player.rotationYaw = input.yaw;
        player.rotationPitch = input.pitch;
        player.onUpdate();
        return snapshot();
    }

    /** Loads {@code start} and returns the state after each input. */
    public List<PlayerState> run(PlayerState start, List<PlayerInput> inputs) {
        load(start);
        List<PlayerState> out = new ArrayList<PlayerState>(inputs.size());
        for (PlayerInput in : inputs) {
            out.add(tick(in));
        }
        return out;
    }

    private void setJumpTicks(int v) {
        try {
            JUMP_TICKS.setInt(player, v);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private int jumpTicks() {
        try {
            return JUMP_TICKS.getInt(player);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
