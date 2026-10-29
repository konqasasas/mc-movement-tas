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
 * no longer turns the view, auto-jump is off, the sprint and use-item key bindings are replaced by
 * ones that follow the script (so no real key event can press or release them), the hotbar number keys
 * do nothing (the script picks the slot), and the
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
    private KeyBinding originalUse;
    private KeyBinding[] originalHotbar;
    private ScriptedMovementInput scripted;
    /** Whether the script held the use-item button on the last tick, so a new hold counts as a press. */
    private boolean useHeld;

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
        // Minecraft.processKeyBinds right-clicks once per press of keyBindUseItem (isPressed) and every
        // 4 ticks while it is held (isKeyDown).
        originalUse = mc.gameSettings.keyBindUseItem;
        ScriptedKey.USE.down = false;
        ScriptedKey.USE.pressed = false;
        useHeld = false;
        mc.gameSettings.keyBindUseItem = ScriptedKey.USE;
        originalHotbar = mc.gameSettings.keyBindsHotbar.clone();
        for (int i = 0; i < originalHotbar.length; i++) {
            mc.gameSettings.keyBindsHotbar[i] = ScriptedKey.HOTBAR[i];
        }
    }

    /** Sets the view angles and the keys for the tick that is about to run, after closing the screen if asked. */
    public void apply(PlayerInput in) {
        if (player == null) {
            return;
        }
        if (in.has(PlayerInput.CLOSE) && mc.currentScreen != null) {
            // As the engine has it: the screen closes before anything else in the tick.
            player.closeScreen();
        }
        player.prevRotationYaw = player.rotationYaw;
        player.prevRotationPitch = player.rotationPitch;
        player.rotationYaw = in.yaw;
        player.rotationPitch = in.pitch;
        scripted.next = in;
        ScriptedKey.SPRINT.down = in.has(PlayerInput.SPRINT);
        // The slot is set before processKeyBinds right-clicks, as a hotbar key press would.
        if (in.slot >= 0) {
            player.inventory.currentItem = in.slot;
        }
        boolean use = in.has(PlayerInput.USE);
        ScriptedKey.USE.down = use;
        ScriptedKey.USE.pressed = use && !useHeld;
        useHeld = use;
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
        if (mc.gameSettings.keyBindUseItem == ScriptedKey.USE) {
            mc.gameSettings.keyBindUseItem = originalUse;
        }
        ScriptedKey.USE.down = false;
        ScriptedKey.USE.pressed = false;
        for (int i = 0; i < originalHotbar.length; i++) {
            if (mc.gameSettings.keyBindsHotbar[i] == ScriptedKey.HOTBAR[i]) {
                mc.gameSettings.keyBindsHotbar[i] = originalHotbar[i];
            }
        }
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
        static final ScriptedKey USE = new ScriptedKey("key.mctas.scriptedUse");
        static final ScriptedKey[] HOTBAR = new ScriptedKey[9];

        static {
            for (int i = 0; i < HOTBAR.length; i++) {
                HOTBAR[i] = new ScriptedKey("key.mctas.scriptedHotbar" + (i + 1));
            }
        }

        boolean down;
        /** One press for {@link #isPressed}, which takes it. */
        boolean pressed;

        private ScriptedKey(String description) {
            super(description, 0, "key.categories.movement");
        }

        @Override
        public boolean isKeyDown() {
            return down;
        }

        @Override
        public boolean isPressed() {
            boolean p = pressed;
            pressed = false;
            return p;
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
