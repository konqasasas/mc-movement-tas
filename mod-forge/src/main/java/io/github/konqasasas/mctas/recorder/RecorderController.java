package io.github.konqasasas.mctas.recorder;

import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.EngineBuild;
import io.github.konqasasas.mctas.physics.Hotbar;
import io.github.konqasasas.mctas.physics.Physics;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.physics.Pressed;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import io.github.konqasasas.mctas.physics.VanillaBlocks;
import io.github.konqasasas.mctas.physics.testing.RandomScenario;
import io.github.konqasasas.mctas.problem.Problem;
import io.github.konqasasas.mctas.problem.Region;
import io.github.konqasasas.mctas.problem.Vec3;
import io.github.konqasasas.mctas.problem.input.TickInput;
import io.github.konqasasas.mctas.problem.io.InputText;
import io.github.konqasasas.mctas.problem.io.ProblemIO;
import io.github.konqasasas.mctas.problem.io.SolutionIO;
import io.github.konqasasas.mctas.problem.judge.Evaluator;
import io.github.konqasasas.mctas.problem.judge.Judge;
import io.github.konqasasas.mctas.problem.judge.PhysicsModel;
import io.github.konqasasas.mctas.problem.judge.RunResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;
import net.minecraft.util.DamageSource;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Runs the recorder's three modes and writes one log per session under {@code .minecraft/mctas/logs}.
 *
 * <ul>
 *   <li>{@code script}: plays an input file from where the player stands.</li>
 *   <li>{@code fuzz}: builds the engine's random test courses next to the player, teleports to each
 *       start and plays its random inputs. The same seeds as the engine's own tests.</li>
 *   <li>{@code record}: follows the person's own play.</li>
 *   <li>{@code replay}: builds a problem's world where the problem puts it, teleports to its spawn and
 *       plays a solution, judging the start and goal entries on the real player.</li>
 * </ul>
 *
 * Every tick the real player's state is compared with the engine's prediction from the previous
 * tick. A mismatch is logged and the engine is resynchronised to the real state, so one run can show
 * several independent mismatches. Changes that happen between ticks (server packets) are logged
 * separately and do not count against the engine.
 */
public final class RecorderController {

    private enum Mode { SCRIPT, FUZZ, RECORD, REPLAY }

    private enum Phase { IDLE, PREPARE, SETTLE, RUN }

    /** Half-width of the area the fuzz mode clears and builds in, around the player's start column. */
    private static final int FUZZ_RADIUS = 20;
    private static final int FUZZ_MIN_Y = 56;
    private static final int FUZZ_MAX_Y = 72;
    private static final int SETTLE_TICKS = 15;
    private static final int SETTLE_TIMEOUT = 100;
    /** What a run that set {@code PlayerState.unsupportedAction} did. */
    private static final String UNSUPPORTED = "the inputs did something the engine does not model (right-clicked a"
            + " block that opens or toggles, placed a block the server may refuse, or let go of sneak under a"
            + " low ceiling)";
    /** Largest area the replay mode clears, in blocks. */
    private static final long REPLAY_MAX_VOLUME = 4000000L;

    private final Minecraft mc = Minecraft.getMinecraft();
    private final InputDriver driver = new InputDriver();

    private Mode mode;
    /** Volatile because the integrated server thread reads it ({@link #onLivingAttack}). */
    private volatile Phase phase = Phase.IDLE;
    private PrintWriter log;
    private File logFile;

    private List<PlayerInput> inputs;
    private int tick;
    private PlayerState sim;
    private PlayerState lastEnd;
    private ClientWorldBlocks blocks;
    private int runMismatches;
    private int runBetween;
    /** Ticks on which a pushable entity was close enough to push the player. */
    private int runEntityTicks;
    private String runFirstMismatch;
    /** A screen opened during a scripted run: someone used the game window, so the run is not left to the script. */
    private boolean runScreenOpened;
    /** The first course block that changed during the run, or null. */
    private String runWorldChange;
    /** The world's randomTickSpeed before the fuzz set it to 0, restored when the fuzz stops. */
    private volatile String savedRandomTickSpeed;
    private int runPausedTicks;
    /** Buttons the engine pressed in this run that the client still shows on, with the tick of the press. */
    private final Map<BlockPos, Integer> runPressTicks = new java.util.LinkedHashMap<BlockPos, Integer>();
    /** Every button the engine pressed in this run: their powered state is not a change of the course. */
    private final java.util.Set<BlockPos> runPressed = new java.util.HashSet<BlockPos>();
    /**
     * For each button kind, the fewest and most ticks after the press at whose start the client first
     * showed the button off, over the whole session: what {@code Pressed.certainTicks} and
     * {@code Pressed.UNCERTAIN_TICKS} must cover.
     */
    private final Map<String, int[]> buttonOffTicks = new java.util.TreeMap<String, int[]>();

    // fuzz
    private long seed;
    private long lastSeed;
    private int fuzzTicks;
    private String fuzzCourse;
    private int originX;
    private int originZ;
    private RandomScenario scenario;
    /** The blocks built for the current run (a fuzz course or a problem world), at {@link #originX}, {@link #originZ}. */
    private SparseBlockWorld course;
    /** What the player holds while settling before a run. */
    private PlayerInput settleInput;
    /** The hotbar the server gives the player for the run, or null to leave the inventory alone. */
    private Hotbar runHotbar;
    /** True while the run's hotbar, slot and placed blocks are compared with the engine. */
    private boolean runPlacing;
    /** The tick on which the engine reached a right-click it does not model, or -1. */
    private int runUnsupportedTick;
    /** The player's own hotbar before the first run that replaced it, put back when the session stops. */
    private volatile net.minecraft.item.ItemStack[] savedHotbar;
    private volatile boolean serverReady;
    private int settleCount;
    private int runsMatched;
    private int runsMismatched;
    private int runsInvalid;
    private int runsSkipped;

    // replay
    private Problem problem;
    private Judge judge;
    private String replayExpected;
    private RunResult enginePrediction;
    /** First tick on which the player was hurt, or -1: damage lets the server overwrite the velocity. */
    private int runDamageTick;
    private boolean replayOffSpawn;

    public boolean active() {
        return phase != Phase.IDLE;
    }

    public void startScript(File file) throws IOException {
        List<PlayerInput> in = TasScript.read(file);
        if (!checkPlayer()) {
            return;
        }
        openLog("script-" + file.getName().replaceAll("[^A-Za-z0-9._-]", "_"));
        log.println("# script " + file.getAbsolutePath() + ", " + in.size() + " ticks");
        mode = Mode.SCRIPT;
        runHotbar = null;
        inputs = in;
        driver.install(mc.player);
        beginRun("script");
        chat("Playing " + in.size() + " ticks from " + file.getName());
    }

    public void startRecord() throws IOException {
        if (!checkPlayer()) {
            return;
        }
        openLog("record");
        mode = Mode.RECORD;
        runHotbar = null;
        inputs = null;
        beginRun("record");
        chat("Recording your play. /mctas stop to finish.");
    }

    /** @param course {@code full}, {@code shapes} or {@code fluids}, as {@link RandomScenario#generate(String, long, int)} */
    public void startFuzz(int runs, int ticks, long firstSeed, String course) throws IOException {
        if (!checkPlayer()) {
            return;
        }
        if (mc.getIntegratedServer() == null) {
            chat("fuzz needs a single-player world.");
            return;
        }
        openLog("fuzz");
        mode = Mode.FUZZ;
        seed = firstSeed;
        lastSeed = firstSeed + runs - 1;
        fuzzTicks = ticks;
        fuzzCourse = course;
        originX = (int) Math.floor(mc.player.posX);
        originZ = (int) Math.floor(mc.player.posZ);
        runsMatched = runsMismatched = runsInvalid = runsSkipped = 0;
        log.println("# fuzz " + course + " seeds " + firstSeed + ".." + lastSeed + ", " + ticks + " ticks, origin " + originX + " " + originZ);
        driver.install(mc.player);
        chat("Fuzz: " + runs + " seeds around " + originX + ", " + originZ + ". You can switch to other windows; do not open menus in this one.");
        prepareNextScenario();
    }

    public void stop(String why) {
        if (phase == Phase.IDLE) {
            return;
        }
        if (phase == Phase.RUN) {
            endRun();
        }
        driver.uninstall();
        restoreDamage();
        phase = Phase.IDLE;
        String summary = mode == Mode.REPLAY ? "replay done"
                : mode == Mode.FUZZ
                ? String.format("fuzz: %d matched, %d mismatched, %d invalid, %d skipped (not standing starts)",
                runsMatched, runsMismatched, runsInvalid, runsSkipped)
                : "stopped";
        if (!buttonOffTicks.isEmpty()) {
            summary += "; buttons off after " + buttonOffSummary();
        }
        if (log != null) {
            log.println("# " + summary + (why == null ? "" : " (" + why + ")"));
            log.close();
            log = null;
        }
        chat(summary + ". Log: " + (logFile == null ? "-" : logFile.getPath()));
    }

    /**
     * No fall damage while any session runs (replay, record, fuzz; D-012). On the integrated server,
     * {@code EntityPlayer.attackEntityFrom} fires this before it hurts the player, and so before
     * {@code EntityLivingBase.attackEntityFrom} marks the velocity changed and the server sends it back
     * to the client, which is what moved replayed falls off the engine's path. Creative mode is not
     * used instead because double-tapping jump toggles flying there.
     */
    @SubscribeEvent
    public void onLivingAttack(LivingAttackEvent event) {
        if (phase != Phase.IDLE && event.getSource() == DamageSource.FALL
                && event.getEntityLiving() instanceof EntityPlayerMP) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (phase == Phase.IDLE) {
            return;
        }
        EntityPlayerSP p = mc.player;
        if (p == null || mc.world == null) {
            stop("left the world");
            return;
        }
        if (mc.isGamePaused()) {
            // The pause menu is open: neither the player nor the server ticks, so neither does the run.
            if (phase == Phase.RUN && event.phase == TickEvent.Phase.START) {
                runPausedTicks++;
            }
            return;
        }
        if (event.phase == TickEvent.Phase.START) {
            onTickStart(p);
        } else {
            onTickEnd(p);
        }
    }

    private void onTickStart(EntityPlayerSP p) {
        if (phase == Phase.SETTLE) {
            driver.apply(settleInput);
            return;
        }
        if (phase != Phase.RUN) {
            return;
        }
        logChanges("before tick " + tick);
        checkPressed();
        PlayerState now = PlayerSnapshot.read(p);
        readPlacement(p, now, lastEnd);
        // The client counts the delay down before this event, so take it from the end of the last tick.
        now.rightClickDelay = lastEnd.rightClickDelay;
        // The server's events for a shulker box may already have been handled this frame; the lid is
        // compared at the end of the tick.
        now.shulker = lastEnd.shulker;
        lastEnd.rotationYaw = now.rotationYaw;
        lastEnd.rotationPitch = now.rotationPitch;
        String field = difference(now, lastEnd);
        if (field != null) {
            runBetween++;
            log.println("# between ticks " + (tick - 1) + " and " + tick + ": " + field + " changed outside the player tick");
            sim = now.copy();
        }
        if (mode != Mode.RECORD) {
            driver.apply(inputs.get(tick));
        }
    }

    private void onTickEnd(EntityPlayerSP p) {
        switch (phase) {
            case PREPARE:
                if (serverReady) {
                    phase = Phase.SETTLE;
                    settleCount = 0;
                }
                return;
            case SETTLE:
                settleCount++;
                // A replay that places blocks also waits for the position resend the problem starts on.
                boolean reported = mode != Mode.REPLAY || runHotbar == null || PlayerSnapshot.reportedAtRest(p);
                if (settleCount >= SETTLE_TICKS && p.onGround && scenarioBuiltOnClient() && reported) {
                    if (mode == Mode.REPLAY) {
                        beginReplay(p);
                    } else {
                        beginRun("seed " + seed);
                    }
                } else if (settleCount >= SETTLE_TIMEOUT && mode == Mode.REPLAY) {
                    log.println("# the world or the player did not settle at the spawn: " + whyNotSettled(p, reported));
                    stop("the player did not come to rest at the spawn, or the world was not built");
                } else if (settleCount >= SETTLE_TIMEOUT) {
                    runsInvalid++;
                    log.println("# seed " + seed + ": course or player did not settle, skipped: " + whyNotSettled(p, reported));
                    advanceFuzz();
                }
                return;
            case RUN:
                PlayerState real = PlayerSnapshot.read(p);
                PlayerInput in = mode == Mode.RECORD ? PlayerSnapshot.lastInput(p) : inputs.get(tick);
                Physics.step(blocks, sim, in);
                logChanges("during tick " + tick);
                notePresses();
                if (sim.unsupportedAction && runUnsupportedTick < 0) {
                    runUnsupportedTick = tick;
                }
                readPlacement(p, real, sim);
                PlayerSnapshot.readShulker(mc, real, sim);
                if (entityNearby(p)) {
                    runEntityTicks++;
                }
                if (mode == Mode.REPLAY && runDamageTick < 0 && p.hurtTime > 0) {
                    runDamageTick = tick;
                }
                if ((mode == Mode.FUZZ || mode == Mode.REPLAY) && runWorldChange == null) {
                    String changed = firstCourseChange();
                    if (changed != null) {
                        runWorldChange = "tick " + tick + " " + changed;
                    }
                }
                String field = difference(sim, real);
                String check = field == null ? "ok" : "DIFF " + field;
                log.println(StateFormat.line(tick, in, real, check));
                if (field != null) {
                    runMismatches++;
                    if (runFirstMismatch == null) {
                        runFirstMismatch = "tick " + tick + " " + field;
                    }
                    log.println("#   engine: " + sim);
                    log.println("#   real:   " + real);
                    logBoxes(p);
                    sim = real.copy();
                }
                lastEnd = real;
                tick++;
                boolean judged = mode == Mode.REPLAY
                        && judge.observe(new Vec3(real.posX, real.posY, real.posZ));
                // After something the engine does not model (a door opened, a refused block) the game and the engine part.
                if (mode != Mode.RECORD && (judged || tick >= inputs.size() || runUnsupportedTick >= 0)) {
                    endRun();
                    // Leave RUN first, or stop() would close the finished run a second time.
                    phase = Phase.PREPARE;
                    if (mode == Mode.FUZZ) {
                        advanceFuzz();
                    } else {
                        stop(null);
                    }
                }
                return;
            default:
        }
    }

    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        if (phase == Phase.RUN && mode != Mode.RECORD && event.getGui() != null) {
            // The screen of a shulker box the engine opened is expected.
            boolean expected = sim != null && sim.shulker.event == io.github.konqasasas.mctas.physics.Shulker.Event.OPEN
                    && event.getGui() instanceof net.minecraft.client.gui.inventory.GuiShulkerBox;
            if (!expected) {
                runScreenOpened = true;
            }
        }
    }

    /**
     * Logs the client's block changes since the last call. The engine reads the world as it was when
     * the run began, so a change here (the client's own button press aside) is a block the real player
     * meets and the engine does not.
     */
    private void logChanges(String when) {
        for (String c : blocks.drainChanges()) {
            log.println("# block changed " + when + ": " + c);
        }
    }

    /**
     * The collision boxes around the player as the client and as the engine see them, with the block
     * at each cell, so a mismatch shows which block one side has and the other does not.
     */
    private void logBoxes(EntityPlayerSP p) {
        net.minecraft.util.math.AxisAlignedBB area = p.getEntityBoundingBox().grow(1.0D);
        StringBuilder real = new StringBuilder();
        for (net.minecraft.util.math.AxisAlignedBB b : mc.world.getCollisionBoxes(p, area)) {
            real.append(String.format(" [%s %s %s -> %s %s %s]", b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ));
        }
        log.println("#   real boxes:" + real);
        StringBuilder engine = new StringBuilder();
        StringBuilder cells = new StringBuilder();
        io.github.konqasasas.mctas.physics.Aabb a = new io.github.konqasasas.mctas.physics.Aabb(area.minX, area.minY,
                area.minZ, area.maxX, area.maxY, area.maxZ);
        for (int x = (int) Math.floor(area.minX) - 1; x <= (int) Math.floor(area.maxX); x++) {
            for (int y = (int) Math.floor(area.minY) - 1; y <= (int) Math.floor(area.maxY); y++) {
                for (int z = (int) Math.floor(area.minZ) - 1; z <= (int) Math.floor(area.maxZ); z++) {
                    BlockType t = blocks.getBlock(x, y, z);
                    List<io.github.konqasasas.mctas.physics.Aabb> out = new java.util.ArrayList<io.github.konqasasas.mctas.physics.Aabb>();
                    t.addCollisionBoxes(blocks, x, y, z, a, out);
                    for (io.github.konqasasas.mctas.physics.Aabb b : out) {
                        engine.append(String.format(" [%s %s %s -> %s %s %s]", b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ));
                    }
                    IBlockState live = mc.world.getBlockState(new BlockPos(x, y, z));
                    String liveName = String.valueOf(ClientWorldBlocks.catalogName(live));
                    if (!t.isAir() || live.getBlock() != Blocks.AIR) {
                        cells.append(' ').append(x).append(',').append(y).append(',').append(z).append('=')
                                .append(t.name().replace("minecraft:", ""));
                        if (!liveName.equals(t.name())) {
                            cells.append("(client now ").append(liveName.replace("minecraft:", "")).append(')');
                        }
                    }
                }
            }
        }
        log.println("#   engine boxes:" + engine);
        log.println("#   cells:" + cells);
        int n = 0;
        for (Entity e : mc.world.getEntitiesWithinAABBExcludingEntity(p, area)) {
            log.println("#   entity: " + e + (e.getCollisionBoundingBox() == null ? "" : " collides"));
            if (++n >= 5) {
                break;
            }
        }
    }

    /** Starts following the buttons the engine pressed on the tick that just ran. */
    private void notePresses() {
        for (int i = 0; i < sim.pressed.size(); i++) {
            // A press ages once at the end of its own tick.
            if (sim.pressed.age(i) == 1) {
                BlockPos pos = new BlockPos(sim.pressed.x(i), sim.pressed.y(i), sim.pressed.z(i));
                runPressTicks.put(pos, tick);
                runPressed.add(pos);
            }
        }
    }

    /**
     * At the start of a tick, after the client took in the server's packets: the state a right-click of
     * this tick sees. A button the engine pressed must still show on for {@code Pressed.certainTicks}
     * ticks and be off again after {@code Pressed.UNCERTAIN_TICKS} more; the tick it turns off is logged
     * and counted for the session summary.
     */
    private void checkPressed() {
        java.util.Iterator<Map.Entry<BlockPos, Integer>> it = runPressTicks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Integer> e = it.next();
            BlockPos pos = e.getKey();
            int k = tick - e.getValue();
            IBlockState st = mc.world.getBlockState(pos);
            String id = String.valueOf(net.minecraft.block.Block.REGISTRY.getNameForObject(st.getBlock()));
            if (!(st.getBlock() instanceof net.minecraft.block.BlockButton)) {
                it.remove();
                continue;
            }
            BlockType type = VanillaBlocks.require(ClientWorldBlocks.catalogName(st));
            int certain = Pressed.certainTicks(type);
            boolean on = st.getValue(net.minecraft.block.BlockButton.POWERED);
            String at = pos.getX() + " " + pos.getY() + " " + pos.getZ();
            if (!on) {
                log.println("# button " + id + " at " + at + " off at the start of tick " + tick + ", " + k
                        + " ticks after the press on tick " + e.getValue());
                int[] mm = buttonOffTicks.get(id);
                if (mm == null) {
                    buttonOffTicks.put(id, new int[] {k, k, 1});
                } else {
                    mm[0] = Math.min(mm[0], k);
                    mm[1] = Math.max(mm[1], k);
                    mm[2]++;
                }
                if (k <= certain) {
                    pressMismatch("button " + at + " off already " + k + " ticks after the press (engine: on until "
                            + certain + ")");
                }
                it.remove();
            } else if (k > certain + Pressed.UNCERTAIN_TICKS) {
                pressMismatch("button " + at + " still on " + k + " ticks after the press (engine: off after "
                        + (certain + Pressed.UNCERTAIN_TICKS) + ")");
                it.remove();
            }
        }
    }

    private void pressMismatch(String what) {
        runMismatches++;
        if (runFirstMismatch == null) {
            runFirstMismatch = "tick " + tick + " pressed: " + what;
        }
        log.println("# DIFF pressed: " + what);
    }

    /** {@link #buttonOffTicks} as "stone_button 18..19 (12 presses), ...". */
    private String buttonOffSummary() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, int[]> e : buttonOffTicks.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            int[] mm = e.getValue();
            sb.append(e.getKey().replace("minecraft:", "")).append(' ').append(mm[0]).append("..").append(mm[1])
                    .append(" ticks (").append(mm[2]).append(" presses)");
        }
        return sb.toString();
    }

    /**
     * Fills in the placement fields of {@code real}. Without a hotbar for the run they are taken from
     * {@code ref} (the person's own inventory is not part of the comparison). With one, the hotbar, slot
     * and delay are read from the client, and the placed blocks are those of {@code ref} that the client
     * world shows: a block the engine placed and the client did not drops out, and one the client placed
     * and the engine did not shows in the hotbar counts.
     */
    private void readPlacement(EntityPlayerSP p, PlayerState real, PlayerState ref) {
        real.prevUse = ref.prevUse;
        real.unsupportedAction = ref.unsupportedAction;
        // The client's buttons are checked against the engine's on their own ({@link #checkPressed}).
        real.pressed = ref.pressed;
        if (!runPlacing) {
            real.hotbar = ref.hotbar;
            real.selectedSlot = ref.selectedSlot;
            real.rightClickDelay = ref.rightClickDelay;
            real.placed = ref.placed;
            real.lastReportedPosX = ref.lastReportedPosX;
            real.lastReportedPosY = ref.lastReportedPosY;
            real.lastReportedPosZ = ref.lastReportedPosZ;
            real.positionUpdateTicks = ref.positionUpdateTicks;
            real.serverSneakState = ref.serverSneakState;
            real.sneakReportedTicks = ref.sneakReportedTicks;
            return;
        }
        PlayerSnapshot.readPlacement(p, real);
        // Not kept by the client; it follows from the sneak states already compared.
        real.sneakReportedTicks = ref.sneakReportedTicks;
        ClientWorldBlocks live = new ClientWorldBlocks(mc.world);
        io.github.konqasasas.mctas.physics.WorldDelta shown = io.github.konqasasas.mctas.physics.WorldDelta.EMPTY;
        for (io.github.konqasasas.mctas.physics.WorldDelta e : ref.placed.entries()) {
            if (live.getBlock(e.x, e.y, e.z) == e.type) {
                shown = shown.with(e.x, e.y, e.z, e.type);
            }
        }
        real.placed = shown;
    }

    /**
     * The first field that differs, then the placed blocks: in a run with a hotbar, a block the engine
     * placed that the client world does not show (or no longer shows, once the server took it back).
     */
    private String difference(PlayerState a, PlayerState b) {
        String field = a.firstDifference(b);
        if (field == null && runPlacing && !a.placed.sameBlocks(b.placed)) {
            field = "placed";
        }
        return field;
    }

        private boolean entityNearby(EntityPlayerSP p) {
        for (Entity e : mc.world.getEntitiesWithinAABBExcludingEntity(p, p.getEntityBoundingBox().grow(1.0D))) {
            if (e.canBePushed()) {
                return true;
            }
        }
        return false;
    }

    private void beginRun(String label) {
        EntityPlayerSP p = mc.player;
        sim = PlayerSnapshot.read(p);
        runPlacing = runHotbar != null;
        runUnsupportedTick = -1;
        if (runPlacing) {
            PlayerSnapshot.readPlacement(p, sim);
        }
        lastEnd = sim.copy();
        blocks = new ClientWorldBlocks(mc.world);
        if (runPlacing || mode == Mode.FUZZ || mode == Mode.REPLAY) {
            // The engine places its own blocks and presses its own buttons; the client's would
            // otherwise be in its way.
            blocks.freeze();
        }
        runPressTicks.clear();
        runPressed.clear();
        tick = 0;
        runMismatches = 0;
        runBetween = 0;
        runEntityTicks = 0;
        runFirstMismatch = null;
        runScreenOpened = false;
        runWorldChange = null;
        runDamageTick = -1;
        runPausedTicks = 0;
        phase = Phase.RUN;
        log.println("# run " + label);
        log.println("# " + StateFormat.HEADER);
        log.println(StateFormat.line(-1, null, sim, "start"));
    }

    private void endRun() {
        blocks.unfreeze();
        if (mc.player != null && mc.currentScreen instanceof net.minecraft.client.gui.inventory.GuiShulkerBox) {
            // A run that stopped with a box open: shut it before the next course is built.
            mc.player.closeScreen();
        }
        if (mode == Mode.REPLAY) {
            endReplay();
            return;
        }
        String result;
        if (runUnsupportedTick >= 0) {
            result = "INVALID: at tick " + runUnsupportedTick + " " + UNSUPPORTED;
            runsInvalid++;
        } else if (!blocks.unsupported().isEmpty()) {
            result = "INVALID: touched unsupported blocks " + blocks.unsupported();
            runsInvalid++;
        } else if (runScreenOpened) {
            result = "INVALID: a screen (menu, chat, inventory) was opened during the run";
            runsInvalid++;
        } else if (runWorldChange != null) {
            // Trampled farmland, a block that fell or got powered: the engine's world stays as built.
            result = "INVALID: the course changed during the run, first at " + runWorldChange;
            runsInvalid++;
        } else if (runEntityTicks > 0) {
            // The engine has no other entities; mobs push the player (collideWithNearbyEntities).
            result = "INVALID: another entity was next to the player on " + runEntityTicks + " ticks";
            runsInvalid++;
        } else if (runMismatches == 0) {
            result = "MATCH " + tick + " ticks";
            runsMatched++;
        } else {
            result = "MISMATCH " + runMismatches + " ticks, first at " + runFirstMismatch;
            runsMismatched++;
        }
        log.println("# result " + result + ", " + runBetween + " between-tick changes"
                + (runPausedTicks > 0 ? ", paused " + runPausedTicks + " ticks" : ""));
        log.flush();
        if (mode != Mode.FUZZ) {
            chat(result);
        }
    }

    /**
     * Plays a solution of a problem on the real player: an {@code mctas-solution} JSON file or a text
     * inputs file. The problem's world is built at its own coordinates, so the area it covers (plus a
     * margin) is overwritten. A solution file made by another engine build is refused unless
     * {@code force}, since its inputs may not do here what they did when it was found.
     */
    public void startReplay(File problemFile, File solutionFile, boolean force) throws IOException {
        if (!checkPlayer()) {
            return;
        }
        if (mc.getIntegratedServer() == null) {
            chat("replay needs a single-player world.");
            return;
        }
        Problem pr;
        List<TickInput> solution;
        JsonObject expected;
        String buildWarning = null;
        try {
            pr = ProblemIO.read(problemFile.toPath());
            String json = new String(java.nio.file.Files.readAllBytes(solutionFile.toPath()), StandardCharsets.UTF_8);
            if (json.trim().startsWith("{")) {
                solution = SolutionIO.readInputs(json);
                String recorded = SolutionIO.readEngineBuild(json);
                buildWarning = SolutionIO.buildMismatch(recorded);
                if (recorded != null && buildWarning != null && !force) {
                    chat("refused: " + buildWarning + ". Rebuild the mod from the same commit as the solution,"
                            + " or add 'force' to replay anyway.");
                    return;
                }
                JsonObject root = new JsonParser().parse(json).getAsJsonObject();
                expected = root.has("result") ? root.getAsJsonObject("result") : new JsonObject();
            } else {
                // A text inputs file (.tas), as the problem tools read it.
                solution = InputText.parse(json, pr.spawn);
                expected = new JsonObject();
            }
        } catch (RuntimeException e) {
            chat("cannot read the problem or the solution: " + e.getMessage());
            return;
        }
        int[] box = replayBox(pr);
        long volume = (long) (box[3] - box[0] + 1) * (box[4] - box[1] + 1) * (box[5] - box[2] + 1);
        if (volume > REPLAY_MAX_VOLUME) {
            chat("the problem's area is too large to build (" + volume + " blocks).");
            return;
        }
        List<PlayerInput> in = new java.util.ArrayList<PlayerInput>();
        for (TickInput t : solution) {
            in.add(PhysicsModel.toPhysics(t));
        }
        openLog("replay-" + pr.id.replaceAll("[^A-Za-z0-9._-]", "_"));
        problem = pr;
        enginePrediction = Evaluator.run(pr, solution);
        replayExpected = expected.has("status") ? describe(expected.get("status").getAsString(),
                intOr(expected, "startTick"), intOr(expected, "goalTick")) : "no result";
        log.println("# replay of " + solutionFile.getAbsolutePath() + " (" + in.size() + " ticks) on problem "
                + pr.id + " from " + problemFile.getAbsolutePath());
        log.println("# solution file says: " + replayExpected);
        if (buildWarning != null) {
            log.println("# warning: " + buildWarning);
            chat("warning: " + buildWarning);
        }
        log.println("# engine predicts:    " + describe(enginePrediction));
        log.println("# cleared and built: " + box[0] + " " + box[1] + " " + box[2] + " .. " + box[3] + " " + box[4] + " " + box[5]);
        mode = Mode.REPLAY;
        inputs = in;
        course = pr.world;
        originX = 0;
        originZ = 0;
        runHotbar = pr.inventory.equals(Hotbar.EMPTY) && pr.selectedSlot == 0 && !rightClicks(in) ? null : pr.inventory;
        settleInput = new PlayerInput(0, pr.spawn.yaw, pr.spawn.pitch, runHotbar == null ? -1 : pr.selectedSlot);
        driver.install(mc.player);
        chat("Replaying " + in.size() + " ticks of " + solutionFile.getName() + " on " + pr.id
                + ". The area " + box[0] + " " + box[1] + " " + box[2] + " .. " + box[3] + " " + box[4] + " " + box[5]
                + " is rebuilt.");
        build(box, pr.spawn.pos.x, pr.spawn.pos.y, pr.spawn.pos.z, pr.spawn.yaw, pr.spawn.pitch, false);
    }

    /** The problem's blocks, spawn, start and goal, with a margin of 3 blocks around and 4 above. */
    private static int[] replayBox(Problem pr) {
        final double[] b = {pr.spawn.pos.x, pr.spawn.pos.y, pr.spawn.pos.z, pr.spawn.pos.x, pr.spawn.pos.y, pr.spawn.pos.z};
        pr.world.forEach(new SparseBlockWorld.Visitor() {
            @Override
            public void visit(int x, int y, int z, BlockType type) {
                grow(b, x, y, z);
                grow(b, x + 1, y + 1, z + 1);
            }
        });
        for (Region r : new Region[] {pr.start, pr.goal}) {
            grow(b, r.minX, r.minY, r.minZ);
            grow(b, r.maxX, r.maxY, r.maxZ);
        }
        return new int[] {
            (int) Math.floor(b[0]) - 3, Math.max(0, (int) Math.floor(b[1]) - 3), (int) Math.floor(b[2]) - 3,
            (int) Math.ceil(b[3]) + 3, Math.min(255, (int) Math.ceil(b[4]) + 4), (int) Math.ceil(b[5]) + 3
        };
    }

    private static void grow(double[] b, double x, double y, double z) {
        b[0] = Math.min(b[0], x);
        b[1] = Math.min(b[1], y);
        b[2] = Math.min(b[2], z);
        b[3] = Math.max(b[3], x);
        b[4] = Math.max(b[4], y);
        b[5] = Math.max(b[5], z);
    }

    private void beginReplay(EntityPlayerSP p) {
        Vec3 spawn = problem.spawn.pos;
        replayOffSpawn = p.posX != spawn.x || p.posY != spawn.y || p.posZ != spawn.z;
        beginRun("replay " + problem.id);
        if (replayOffSpawn) {
            log.println("# the player rests at " + p.posX + " " + p.posY + " " + p.posZ + ", not at the spawn "
                    + spawn.x + " " + spawn.y + " " + spawn.z);
        }
        judge = new Judge(problem);
    }

    private void endReplay() {
        RunResult real = judge.result();
        List<String> notes = new java.util.ArrayList<String>();
        if (replayOffSpawn) {
            notes.add("the player did not start exactly at the spawn");
        }
        if (runUnsupportedTick >= 0) {
            notes.add("stopped at tick " + runUnsupportedTick + ": " + UNSUPPORTED);
        }
        if (!blocks.unsupported().isEmpty()) {
            notes.add("touched unsupported blocks " + blocks.unsupported());
        }
        if (runScreenOpened) {
            notes.add("a screen was opened");
        }
        if (runWorldChange != null) {
            notes.add("the world changed, first at " + runWorldChange);
        }
        if (runEntityTicks > 0) {
            notes.add("another entity was next to the player on " + runEntityTicks + " ticks");
        }
        if (runDamageTick >= 0) {
            notes.add("the player was hurt at tick " + runDamageTick);
        }
        notes.add(runMismatches == 0 ? "the engine matched every tick"
                : "the engine differed on " + runMismatches + " ticks, first at " + runFirstMismatch);
        String result = "real game: " + describe(real) + "; solution file: " + replayExpected
                + "; engine: " + describe(enginePrediction);
        log.println("# result " + result);
        for (String n : notes) {
            log.println("# note " + n);
        }
        log.flush();
        chat(result);
        for (String n : notes) {
            chat("  " + n);
        }
    }

    private static String describe(RunResult r) {
        return describe(r.status.name(), r.finished() ? r.startTick : null, r.finished() ? r.goalTick : null);
    }

    private static String describe(String status, Integer start, Integer goal) {
        String st = status.toUpperCase(java.util.Locale.ROOT);
        if (start == null || goal == null) {
            return st;
        }
        return st + " start " + start + ", goal " + goal + " (" + (goal - start) + " ticks)";
    }

    private static Integer intOr(JsonObject o, String key) {
        return o.has(key) ? Integer.valueOf(o.get(key).getAsInt()) : null;
    }

    private void advanceFuzz() {
        seed++;
        if (seed > lastSeed) {
            stop(null);
        } else {
            if ((seed - 1) % 50 == 0) {
                chat(String.format("fuzz progress: %d matched, %d mismatched, %d invalid", runsMatched, runsMismatched, runsInvalid));
            }
            prepareNextScenario();
        }
    }

    private void prepareNextScenario() {
        while (seed <= lastSeed) {
            RandomScenario sc = RandomScenario.generate(fuzzCourse, seed, fuzzTicks);
            if (sc.standingStart) {
                scenario = sc;
                break;
            }
            runsSkipped++;
            seed++;
        }
        if (seed > lastSeed) {
            stop(null);
            return;
        }
        inputs = scenario.inputs;
        course = scenario.world;
        runHotbar = scenario.start.hotbar.equals(Hotbar.EMPTY) && !rightClicks(inputs) ? null : scenario.start.hotbar;
        settleInput = new PlayerInput(0, scenario.start.rotationYaw, 0.0F,
                runHotbar == null ? -1 : scenario.start.selectedSlot);
        PlayerState st = scenario.start;
        build(new int[] {originX - FUZZ_RADIUS, FUZZ_MIN_Y, originZ - FUZZ_RADIUS,
                originX + FUZZ_RADIUS, FUZZ_MAX_Y, originZ + FUZZ_RADIUS},
                st.posX + originX, st.posY, st.posZ + originZ, st.rotationYaw, 0.0F, true);
    }

    /**
     * Clears the box (inclusive block coordinates minX, minY, minZ, maxX, maxY, maxZ), builds
     * {@link #course} at the origin and teleports the player, on the server thread; then waits in
     * PREPARE until that is done.
     */
    private void build(final int[] box, final double x, final double y, final double z, final float yaw,
                       final float pitch, final boolean invulnerable) {
        phase = Phase.PREPARE;
        serverReady = false;
        final SparseBlockWorld world = course;
        final int ox = originX;
        final int oz = originZ;
        final IntegratedServer server = mc.getIntegratedServer();
        final java.util.UUID id = mc.player.getUniqueID();
        final Hotbar hotbar = runHotbar;
        server.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                buildOnServer(server, id, world, ox, oz, box, x, y, z, yaw, pitch, invulnerable);
                if (hotbar != null) {
                    giveHotbar(server, id, hotbar);
                }
                serverReady = true;
            }
        });
    }

    private void buildOnServer(IntegratedServer server, java.util.UUID id, SparseBlockWorld world, final int ox,
                               final int oz, int[] box, double px, double py, double pz, float yaw, float pitch,
                               boolean invulnerable) {
        final WorldServer ws = server.getWorld(0);
        final IBlockState air = Blocks.AIR.getDefaultState();
        // No random ticks: grass spreading, crops growing, snow and ice melting would change the course.
        if (savedRandomTickSpeed == null) {
            savedRandomTickSpeed = ws.getGameRules().getString("randomTickSpeed");
        }
        ws.getGameRules().setOrCreateGameRule("randomTickSpeed", "0");
        for (int x = box[0]; x <= box[3]; x++) {
            for (int z = box[2]; z <= box[5]; z++) {
                for (int y = box[1]; y <= box[4]; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (ws.getBlockState(pos) != air) {
                        ws.setBlockState(pos, air, 2);
                    }
                }
            }
        }
        world.forEach(new SparseBlockWorld.Visitor() {
            @Override
            public void visit(int x, int y, int z, BlockType type) {
                ws.setBlockState(new BlockPos(x + ox, y, z + oz), vanilla(type), 2);
            }
        });
        // Placing a block can schedule a tick for it (BlockFalling.onBlockAdded, fire, liquids): a floating
        // anvil or concrete powder would then fall and the course never settle. The engine's world never
        // changes, so drop every tick scheduled in the box.
        ws.getPendingBlockUpdates(new net.minecraft.world.gen.structure.StructureBoundingBox(
                box[0], box[1], box[2], box[3] + 1, box[4] + 1, box[5] + 1), true);
        EntityPlayerMP mp = server.getPlayerList().getPlayerByUUID(id);
        if (mp != null) {
            if (invulnerable) {
                mp.capabilities.disableDamage = true;
            }
            mp.setHealth(mp.getMaxHealth());
            mp.getFoodStats().setFoodLevel(20);
            mp.fallDistance = 0.0F;
            mp.connection.setPlayerLocation(px, py, pz, yaw, pitch);
        }
    }

    /**
     * True if the inputs right-click. Such a run gets its own hotbar even when it is empty: with the
     * player's own blocks in hand, a click on a button or the floor places one of them instead.
     */
    private static boolean rightClicks(List<PlayerInput> inputs) {
        for (PlayerInput in : inputs) {
            if (in.has(PlayerInput.USE)) {
                return true;
            }
        }
        return false;
    }

    /** Puts {@code hotbar} into the player's hotbar on the server, keeping the player's own for later. */
    private void giveHotbar(IntegratedServer server, java.util.UUID id, Hotbar hotbar) {
        EntityPlayerMP mp = server.getPlayerList().getPlayerByUUID(id);
        if (mp == null) {
            return;
        }
        if (savedHotbar == null) {
            net.minecraft.item.ItemStack[] own = new net.minecraft.item.ItemStack[Hotbar.SLOTS];
            for (int i = 0; i < Hotbar.SLOTS; i++) {
                own[i] = mp.inventory.getStackInSlot(i).copy();
            }
            savedHotbar = own;
        }
        for (int i = 0; i < Hotbar.SLOTS; i++) {
            BlockType t = hotbar.item(i);
            net.minecraft.item.ItemStack st = net.minecraft.item.ItemStack.EMPTY;
            if (t != null) {
                IBlockState s = vanilla(t);
                st = new net.minecraft.item.ItemStack(s.getBlock(), hotbar.count(i), s.getBlock().getMetaFromState(s));
            }
            mp.inventory.setInventorySlotContents(i, st);
        }
        mp.inventoryContainer.detectAndSendChanges();
    }

    /** Which of the settle conditions still fails, for the log. */
    private String whyNotSettled(EntityPlayerSP p, boolean reported) {
        List<String> why = new java.util.ArrayList<String>();
        if (!p.onGround) {
            why.add("player not on the ground at " + p.posX + " " + p.posY + " " + p.posZ);
        }
        String changed = firstCourseChange();
        if (changed != null) {
            why.add("course block differs at " + changed);
        }
        if (runHotbar != null) {
            PlayerState h = new PlayerState();
            PlayerSnapshot.readPlacement(p, h);
            if (!h.hotbar.equals(runHotbar)) {
                why.add("hotbar " + h.hotbar + " is not " + runHotbar);
            }
        }
        if (!reported) {
            why.add("position resend not yet aligned");
        }
        return why.isEmpty() ? "unknown" : String.join("; ", why);
    }

    private boolean scenarioBuiltOnClient() {
        if (runHotbar != null && mc.player != null) {
            // The hotbar reaches the client in its own packets, after the blocks or before them.
            PlayerState h = new PlayerState();
            PlayerSnapshot.readPlacement(mc.player, h);
            if (!h.hotbar.equals(runHotbar)) {
                return false;
            }
        }
        return firstCourseChange() == null;
    }

    /** The first course block that the client world does not show as built, as "x y z: built -> now", or null. */
    private String firstCourseChange() {
        final String[] first = {null};
        final ClientWorldBlocks check = new ClientWorldBlocks(mc.world);
        course.forEach(new SparseBlockWorld.Visitor() {
            @Override
            public void visit(int x, int y, int z, BlockType type) {
                if (first[0] == null) {
                    if (phase == Phase.RUN && sim != null && sim.placed.get(x + originX, y, z + originZ) != null) {
                        // Placed into a replaceable block (tall grass, snow) on purpose.
                        return;
                    }
                    BlockType now = check.getBlock(x + originX, y, z + originZ);
                    if (phase == Phase.RUN && runPressed.contains(new BlockPos(x + originX, y, z + originZ))
                            && VanillaBlocks.blockId(now.name()).equals(VanillaBlocks.blockId(type.name()))) {
                        // A button the engine pressed: checkPressed follows it.
                        return;
                    }
                    if (now != type && !VanillaBlocks.storedName(now).equals(VanillaBlocks.storedName(type))) {
                        first[0] = (x + originX) + " " + y + " " + (z + originZ) + ": " + type + " -> " + now;
                    }
                }
            }
        });
        return first[0];
    }

    private static IBlockState vanilla(BlockType type) {
        return VanillaStates.of(type);
    }

    /**
     * The fuzz mode makes the player invulnerable on the server so falls do not end the session, and
     * turns random ticks off; this undoes both.
     */
    private void restoreDamage() {
        final IntegratedServer server = mc.getIntegratedServer();
        if (server == null || mc.player == null || (mode != Mode.FUZZ && mode != Mode.REPLAY)) {
            return;
        }
        final java.util.UUID id = mc.player.getUniqueID();
        server.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                EntityPlayerMP mp = server.getPlayerList().getPlayerByUUID(id);
                if (mp != null) {
                    mp.capabilities.disableDamage = false;
                    if (savedHotbar != null) {
                        for (int i = 0; i < Hotbar.SLOTS; i++) {
                            mp.inventory.setInventorySlotContents(i, savedHotbar[i]);
                        }
                        mp.inventoryContainer.detectAndSendChanges();
                        savedHotbar = null;
                    }
                }
                if (savedRandomTickSpeed != null) {
                    server.getWorld(0).getGameRules().setOrCreateGameRule("randomTickSpeed", savedRandomTickSpeed);
                    savedRandomTickSpeed = null;
                }
            }
        });
    }

    private boolean checkPlayer() {
        if (phase != Phase.IDLE) {
            chat("Already running. /mctas stop first.");
            return false;
        }
        if (mc.player == null) {
            return false;
        }
        if (!mc.playerController.isNotCreative() || mc.player.capabilities.allowFlying) {
            chat("Switch to survival mode first: creative flight changes the movement rules.");
            return false;
        }
        return true;
    }

    private void openLog(String name) throws IOException {
        File dir = new File(mc.gameDir, "mctas/logs");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        logFile = new File(dir, name + "-" + stamp + ".txt");
        log = new PrintWriter(new OutputStreamWriter(new FileOutputStream(logFile), StandardCharsets.UTF_8));
        log.println("# mctas recorder, Minecraft 1.12.2, engine build " + EngineBuild.ID);
    }

    private void chat(String msg) {
        if (mc.player != null) {
            mc.player.sendMessage(new TextComponentString("[mctas] " + msg));
        }
    }
}
