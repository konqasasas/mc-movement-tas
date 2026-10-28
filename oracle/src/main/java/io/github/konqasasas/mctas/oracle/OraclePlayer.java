package io.github.konqasasas.mctas.oracle;

import com.mojang.authlib.GameProfile;
import io.github.konqasasas.mctas.physics.PlayerInput;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.UUID;

/**
 * A vanilla {@code EntityPlayer} driven the way {@code EntityPlayerSP} drives the client player.
 *
 * <p>Everything from {@code EntityPlayer} down runs the game's own bytecode. The server jar has no
 * {@code EntityPlayerSP}, so its movement-input, push-out-of-blocks and sprint code is ported by hand
 * below; that part is checked against the real client separately.
 */
final class OraclePlayer extends net.minecraft.entity.player.EntityPlayer {

    PlayerInput input = PlayerInput.of(0, 0.0F);

    // EntityPlayerSP.movementInput and sprintToggleTimer
    float miStrafe;
    float miForward;
    boolean miJump;
    boolean miSneak;
    int sprintToggleTimer;

    OraclePlayer(World world) {
        super(world, new GameProfile(UUID.fromString("00000000-0000-0000-0000-000000000001"), "oracle"));
    }

    @Override
    public boolean isSpectator() {
        return false;
    }

    @Override
    public boolean isCreative() {
        return false;
    }

    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {
        return false;
    }

    @Override
    public void addExhaustion(float exhaustion) {
    }

    /** What the last {@link #displayGUIChest} opened, for the server's side of a click on a shulker box. */
    net.minecraft.inventory.IInventory displayed;

    @Override
    public void displayGUIChest(net.minecraft.inventory.IInventory chestInventory) {
        displayed = chestInventory;
    }

    /**
     * {@code EntityLivingBase.updateFallState} as the client runs it: the server-only landing
     * particles cast the world to {@code WorldServer}, so they are left out.
     */
    @Override
    protected void updateFallState(double y, boolean onGroundIn, IBlockState state, BlockPos pos) {
        if (!this.isInWater()) {
            this.handleWaterMovement();
        }
        // Entity.updateFallState
        if (onGroundIn) {
            if (this.fallDistance > 0.0F) {
                state.getBlock().onFallenUpon(this.world, pos, this, this.fallDistance);
            }
            this.fallDistance = 0.0F;
        } else if (y < 0.0D) {
            this.fallDistance = (float) ((double) this.fallDistance - y);
        }
    }

    /** {@code Entity.getVectorForRotation} at the current angles, which is protected. */
    net.minecraft.util.math.Vec3d look() {
        return getVectorForRotation(rotationPitch, rotationYaw);
    }

    @Override
    public boolean isSneaking() {
        return miSneak;
    }

    @Override
    public void onLivingUpdate() {
        if (this.sprintToggleTimer > 0) {
            --this.sprintToggleTimer;
        }
        boolean flag1 = this.miSneak;
        boolean flag2 = this.miForward >= 0.8F;

        // MovementInputFromOptions.updatePlayerMoveState
        this.miStrafe = 0.0F;
        this.miForward = 0.0F;
        if (input.has(PlayerInput.W)) {
            ++this.miForward;
        }
        if (input.has(PlayerInput.S)) {
            --this.miForward;
        }
        if (input.has(PlayerInput.A)) {
            ++this.miStrafe;
        }
        if (input.has(PlayerInput.D)) {
            --this.miStrafe;
        }
        this.miJump = input.has(PlayerInput.JUMP);
        this.miSneak = input.has(PlayerInput.SNEAK);
        if (this.miSneak) {
            this.miStrafe = (float) ((double) this.miStrafe * 0.3D);
            this.miForward = (float) ((double) this.miForward * 0.3D);
        }

        AxisAlignedBB axisalignedbb = this.getEntityBoundingBox();
        this.pushOutOfBlocks(this.posX - (double) this.width * 0.35D, axisalignedbb.minY + 0.5D, this.posZ + (double) this.width * 0.35D);
        this.pushOutOfBlocks(this.posX - (double) this.width * 0.35D, axisalignedbb.minY + 0.5D, this.posZ - (double) this.width * 0.35D);
        this.pushOutOfBlocks(this.posX + (double) this.width * 0.35D, axisalignedbb.minY + 0.5D, this.posZ - (double) this.width * 0.35D);
        this.pushOutOfBlocks(this.posX + (double) this.width * 0.35D, axisalignedbb.minY + 0.5D, this.posZ + (double) this.width * 0.35D);
        boolean flag4 = (float) this.getFoodStats().getFoodLevel() > 6.0F || this.capabilities.allowFlying;
        boolean sprintKey = input.has(PlayerInput.SPRINT);

        if (this.onGround && !flag1 && !flag2 && this.miForward >= 0.8F && !this.isSprinting() && flag4 && !this.isHandActive()) {
            if (this.sprintToggleTimer <= 0 && !sprintKey) {
                this.sprintToggleTimer = 7;
            } else {
                this.setSprinting(true);
            }
        }
        if (!this.isSprinting() && this.miForward >= 0.8F && flag4 && !this.isHandActive() && sprintKey) {
            this.setSprinting(true);
        }
        if (this.isSprinting() && (this.miForward < 0.8F || this.collidedHorizontally || !flag4)) {
            this.setSprinting(false);
        }

        super.onLivingUpdate();
    }

    @Override
    protected void updateEntityActionState() {
        super.updateEntityActionState();
        this.moveStrafing = this.miStrafe;
        this.moveForward = this.miForward;
        this.isJumping = this.miJump;
    }

    /** {@code EntityPlayerSP.pushOutOfBlocks}. */
    @Override
    protected boolean pushOutOfBlocks(double x, double y, double z) {
        if (this.noClip) {
            return false;
        }
        BlockPos blockpos = new BlockPos(x, y, z);
        double d0 = x - (double) blockpos.getX();
        double d1 = z - (double) blockpos.getZ();
        if (!this.isOpenBlockSpace(blockpos)) {
            int i = -1;
            double d2 = 9999.0D;
            if (this.isOpenBlockSpace(blockpos.west()) && d0 < d2) {
                d2 = d0;
                i = 0;
            }
            if (this.isOpenBlockSpace(blockpos.east()) && 1.0D - d0 < d2) {
                d2 = 1.0D - d0;
                i = 1;
            }
            if (this.isOpenBlockSpace(blockpos.north()) && d1 < d2) {
                d2 = d1;
                i = 4;
            }
            if (this.isOpenBlockSpace(blockpos.south()) && 1.0D - d1 < d2) {
                i = 5;
            }
            if (i == 0) {
                this.motionX = -0.10000000149011612D;
            }
            if (i == 1) {
                this.motionX = 0.10000000149011612D;
            }
            if (i == 4) {
                this.motionZ = -0.10000000149011612D;
            }
            if (i == 5) {
                this.motionZ = 0.10000000149011612D;
            }
        }
        return false;
    }

    private boolean isOpenBlockSpace(BlockPos pos) {
        IBlockState a = this.world.getBlockState(pos);
        IBlockState b = this.world.getBlockState(pos.up());
        return !a.isNormalCube() && !b.isNormalCube();
    }

    boolean inWebFlag() {
        return isInWeb;
    }

    void setInWebFlag(boolean v) {
        isInWeb = v;
    }

    boolean inWaterFlag() {
        return inWater;
    }

    void setInWaterFlag(boolean v) {
        inWater = v;
    }
}
