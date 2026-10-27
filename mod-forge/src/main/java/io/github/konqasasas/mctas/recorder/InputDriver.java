package io.github.konqasasas.mctas.recorder;

import io.github.konqasasas.mctas.physics.PlayerInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.MouseHelper;
import net.minecraft.util.MovementInput;
import org.lwjgl.input.Mouse;

/**
 * Feeds one {@link PlayerInput} per tick into the real client player, the way a person holding the
 * keys and turning the mouse would. Meant to be reused by the replay mod.
 *
 * <p>While installed: the player's {@code movementInput} is replaced by a scripted one, the mouse
 * no longer turns the view, auto-jump is off, and the sprint key binding follows the script. Call
 * {@link #apply} at the start of each client tick.
 */
public final class InputDriver {

    private final Minecraft mc = Minecraft.getMinecraft();
    private EntityPlayerSP player;
    private MovementInput originalInput;
    private MouseHelper originalMouse;
    private boolean originalAutoJump;
    private ScriptedMovementInput scripted;

    public boolean installed() {
        return player != null;
    }

    public void install(EntityPlayerSP p) {
        if (player != null) {
            return;
        }
        player = p;
        originalInput = p.movementInput;
        scripted = new ScriptedMovementInput();
        p.movementInput = scripted;
        originalMouse = mc.mouseHelper;
        mc.mouseHelper = new FrozenMouse();
        originalAutoJump = mc.gameSettings.autoJump;
        mc.gameSettings.autoJump = false;
    }

    /** Sets the view angles and the keys for the tick that is about to run. */
    public void apply(PlayerInput in) {
        if (player == null) {
            return;
        }
        player.prevRotationYaw = player.rotationYaw;
        player.prevRotationPitch = player.rotationPitch;
        player.rotationYaw = in.yaw;
        player.rotationPitch = in.pitch;
        scripted.next = in;
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), in.has(PlayerInput.SPRINT));
    }

    public void uninstall() {
        if (player == null) {
            return;
        }
        if (player.movementInput == scripted) {
            player.movementInput = originalInput;
        }
        mc.mouseHelper = originalMouse;
        mc.gameSettings.autoJump = originalAutoJump;
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), false);
        player = null;
        scripted = null;
    }

    /** {@code MovementInputFromOptions.updatePlayerMoveState} with keys from the script. */
    static final class ScriptedMovementInput extends MovementInput {

        PlayerInput next = PlayerInput.of(0, 0.0F);

        @Override
        public void updatePlayerMoveState() {
            PlayerInput in = next;
            this.moveStrafe = 0.0F;
            this.moveForward = 0.0F;
            this.forwardKeyDown = in.has(PlayerInput.W);
            this.backKeyDown = in.has(PlayerInput.S);
            this.leftKeyDown = in.has(PlayerInput.A);
            this.rightKeyDown = in.has(PlayerInput.D);
            if (this.forwardKeyDown) {
                ++this.moveForward;
            }
            if (this.backKeyDown) {
                --this.moveForward;
            }
            if (this.leftKeyDown) {
                ++this.moveStrafe;
            }
            if (this.rightKeyDown) {
                --this.moveStrafe;
            }
            this.jump = in.has(PlayerInput.JUMP);
            this.sneak = in.has(PlayerInput.SNEAK);
            if (this.sneak) {
                this.moveStrafe = (float) ((double) this.moveStrafe * 0.3D);
                this.moveForward = (float) ((double) this.moveForward * 0.3D);
            }
        }
    }

    /** Swallows mouse movement so only the script turns the view. */
    static final class FrozenMouse extends MouseHelper {
        @Override
        public void mouseXYChange() {
            Mouse.getDX();
            Mouse.getDY();
            this.deltaX = 0;
            this.deltaY = 0;
        }
    }
}
