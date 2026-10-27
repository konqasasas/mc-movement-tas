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
 * no longer turns the view, auto-jump is off, the sprint key binding is replaced by one that follows
 * the script (so no real key event can press or release it), and the
 * game no longer opens the pause menu when its window loses focus, so a run can go on while the person
 * uses other windows. Call {@link #apply} at the start of each client tick.
 */
public final class InputDriver {

    private final Minecraft mc = Minecraft.getMinecraft();
    private EntityPlayerSP player;
    private MovementInput originalInput;
    private MouseHelper originalMouse;
    private boolean originalAutoJump;
    private boolean originalPauseOnLostFocus;
    private KeyBinding originalSprint;
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
        // EntityRenderer.updateCameraAndRender opens the pause menu half a second after the window
        // loses focus, which would pause the integrated server and the player in the middle of a run.
        originalPauseOnLostFocus = mc.gameSettings.pauseOnLostFocus;
        mc.gameSettings.pauseOnLostFocus = false;
        // EntityPlayerSP reads gameSettings.keyBindSprint.isKeyDown(). The real binding follows every
        // keyboard event, which arrive after the start of the tick: with the window in the background
        // something kept releasing it, and the player never sprinted from the script.
        originalSprint = mc.gameSettings.keyBindSprint;
        ScriptedKey.SPRINT.down = false;
        mc.gameSettings.keyBindSprint = ScriptedKey.SPRINT;
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
        ScriptedKey.SPRINT.down = in.has(PlayerInput.SPRINT);
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
        mc.gameSettings.pauseOnLostFocus = originalPauseOnLostFocus;
        if (mc.gameSettings.keyBindSprint == ScriptedKey.SPRINT) {
            mc.gameSettings.keyBindSprint = originalSprint;
        }
        ScriptedKey.SPRINT.down = false;
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

    /**
     * A key binding held or released only by the script. It has no key code, so keyboard events never
     * reach it, and it is not in the options list, so the controls screen and options file ignore it.
     */
    static final class ScriptedKey extends KeyBinding {
        static final ScriptedKey SPRINT = new ScriptedKey("key.mctas.scriptedSprint");

        boolean down;

        private ScriptedKey(String description) {
            super(description, 0, "key.categories.movement");
        }

        @Override
        public boolean isKeyDown() {
            return down;
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
