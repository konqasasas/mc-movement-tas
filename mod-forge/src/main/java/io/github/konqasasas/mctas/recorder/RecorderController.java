package io.github.konqasasas.mctas.recorder;

import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.Physics;
import io.github.konqasasas.mctas.physics.PlayerInput;
import io.github.konqasasas.mctas.physics.PlayerState;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import io.github.konqasasas.mctas.physics.VanillaBlocks;
import io.github.konqasasas.mctas.physics.testing.RandomScenario;
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
import net.minecraftforge.client.event.GuiOpenEvent;
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

/**
 * Runs the recorder's three modes and writes one log per session under {@code .minecraft/mctas/logs}.
 *
 * <ul>
 *   <li>{@code script}: plays an input file from where the player stands.</li>
 *   <li>{@code fuzz}: builds the engine's random test courses next to the player, teleports to each
 *       start and plays its random inputs. The same seeds as the engine's own tests.</li>
 *   <li>{@code record}: follows the person's own play.</li>
 * </ul>
 *
 * Every tick the real player's state is compared with the engine's prediction from the previous
 * tick. A mismatch is logged and the engine is resynchronised to the real state, so one run can show
 * several independent mismatches. Changes that happen between ticks (server packets) are logged
 * separately and do not count against the engine.
 */
public final class RecorderController {

    private enum Mode { SCRIPT, FUZZ, RECORD }

    private enum Phase { IDLE, PREPARE, SETTLE, RUN }

    /** Half-width of the area the fuzz mode clears and builds in, around the player's start column. */
    private static final int FUZZ_RADIUS = 20;
    private static final int FUZZ_MIN_Y = 56;
    private static final int FUZZ_MAX_Y = 72;
    private static final int SETTLE_TICKS = 15;
    private static final int SETTLE_TIMEOUT = 100;

    private final Minecraft mc = Minecraft.getMinecraft();
    private final InputDriver driver = new InputDriver();

    private Mode mode;
    private Phase phase = Phase.IDLE;
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

    // fuzz
    private long seed;
    private long lastSeed;
    private int fuzzTicks;
    private String fuzzCourse;
    private int originX;
    private int originZ;
    private RandomScenario scenario;
    private volatile boolean serverReady;
    private int settleCount;
    private int runsMatched;
    private int runsMismatched;
    private int runsInvalid;
    private int runsSkipped;

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
        String summary = mode == Mode.FUZZ
                ? String.format("fuzz: %d matched, %d mismatched, %d invalid, %d skipped (not standing starts)",
                runsMatched, runsMismatched, runsInvalid, runsSkipped)
                : "stopped";
        if (log != null) {
            log.println("# " + summary + (why == null ? "" : " (" + why + ")"));
            log.close();
            log = null;
        }
        chat(summary + ". Log: " + (logFile == null ? "-" : logFile.getPath()));
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
            driver.apply(PlayerInput.of(0, scenario.start.rotationYaw));
            return;
        }
        if (phase != Phase.RUN) {
            return;
        }
        PlayerState now = PlayerSnapshot.read(p);
        lastEnd.rotationYaw = now.rotationYaw;
        lastEnd.rotationPitch = now.rotationPitch;
        String field = now.firstDifference(lastEnd);
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
                if (settleCount >= SETTLE_TICKS && p.onGround && scenarioBuiltOnClient()) {
                    beginRun("seed " + seed);
                } else if (settleCount >= SETTLE_TIMEOUT) {
                    runsInvalid++;
                    log.println("# seed " + seed + ": course or player did not settle, skipped");
                    advanceFuzz();
                }
                return;
            case RUN:
                PlayerState real = PlayerSnapshot.read(p);
                PlayerInput in = mode == Mode.RECORD ? PlayerSnapshot.lastInput(p) : inputs.get(tick);
                Physics.step(blocks, sim, in);
                if (entityNearby(p)) {
                    runEntityTicks++;
                }
                if (mode == Mode.FUZZ && runWorldChange == null) {
                    String changed = firstCourseChange();
                    if (changed != null) {
                        runWorldChange = "tick " + tick + " " + changed;
                    }
                }
                String field = sim.firstDifference(real);
                String check = field == null ? "ok" : "DIFF " + field;
                log.println(StateFormat.line(tick, in, real, check));
                if (field != null) {
                    runMismatches++;
                    if (runFirstMismatch == null) {
                        runFirstMismatch = "tick " + tick + " " + field;
                    }
                    log.println("#   engine: " + sim);
                    log.println("#   real:   " + real);
                    sim = real.copy();
                }
                lastEnd = real;
                tick++;
                if (mode != Mode.RECORD && tick >= inputs.size()) {
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
            runScreenOpened = true;
        }
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
        lastEnd = sim.copy();
        blocks = new ClientWorldBlocks(mc.world);
        tick = 0;
        runMismatches = 0;
        runBetween = 0;
        runEntityTicks = 0;
        runFirstMismatch = null;
        runScreenOpened = false;
        runWorldChange = null;
        runPausedTicks = 0;
        phase = Phase.RUN;
        log.println("# run " + label);
        log.println("# " + StateFormat.HEADER);
        log.println(StateFormat.line(-1, null, sim, "start"));
    }

    private void endRun() {
        String result;
        if (!blocks.unsupported().isEmpty()) {
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
        phase = Phase.PREPARE;
        serverReady = false;
        final RandomScenario sc = scenario;
        final IntegratedServer server = mc.getIntegratedServer();
        final java.util.UUID id = mc.player.getUniqueID();
        server.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                buildOnServer(server, id, sc);
                serverReady = true;
            }
        });
    }

    private void buildOnServer(IntegratedServer server, java.util.UUID id, RandomScenario sc) {
        final WorldServer ws = server.getWorld(0);
        final IBlockState air = Blocks.AIR.getDefaultState();
        // No random ticks: grass spreading, crops growing, snow and ice melting would change the course.
        if (savedRandomTickSpeed == null) {
            savedRandomTickSpeed = ws.getGameRules().getString("randomTickSpeed");
        }
        ws.getGameRules().setOrCreateGameRule("randomTickSpeed", "0");
        for (int x = originX - FUZZ_RADIUS; x <= originX + FUZZ_RADIUS; x++) {
            for (int z = originZ - FUZZ_RADIUS; z <= originZ + FUZZ_RADIUS; z++) {
                for (int y = FUZZ_MIN_Y; y <= FUZZ_MAX_Y; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (ws.getBlockState(pos) != air) {
                        ws.setBlockState(pos, air, 2);
                    }
                }
            }
        }
        sc.world.forEach(new SparseBlockWorld.Visitor() {
            @Override
            public void visit(int x, int y, int z, BlockType type) {
                ws.setBlockState(new BlockPos(x + originX, y, z + originZ), vanilla(type), 2);
            }
        });
        EntityPlayerMP mp = server.getPlayerList().getPlayerByUUID(id);
        if (mp != null) {
            mp.capabilities.disableDamage = true;
            mp.getFoodStats().setFoodLevel(20);
            mp.fallDistance = 0.0F;
            mp.connection.setPlayerLocation(sc.start.posX + originX, sc.start.posY, sc.start.posZ + originZ,
                    sc.start.rotationYaw, 0.0F);
        }
    }

    private boolean scenarioBuiltOnClient() {
        return firstCourseChange() == null;
    }

    /** The first course block that the client world does not show as built, as "x y z: built -> now", or null. */
    private String firstCourseChange() {
        final String[] first = {null};
        final ClientWorldBlocks check = new ClientWorldBlocks(mc.world);
        scenario.world.forEach(new SparseBlockWorld.Visitor() {
            @Override
            public void visit(int x, int y, int z, BlockType type) {
                if (first[0] == null) {
                    BlockType now = check.getBlock(x + originX, y, z + originZ);
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
        if (server == null || mc.player == null || mode != Mode.FUZZ) {
            return;
        }
        final java.util.UUID id = mc.player.getUniqueID();
        server.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                EntityPlayerMP mp = server.getPlayerList().getPlayerByUUID(id);
                if (mp != null) {
                    mp.capabilities.disableDamage = false;
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
        log.println("# mctas recorder, Minecraft 1.12.2");
    }

    private void chat(String msg) {
        if (mc.player != null) {
            mc.player.sendMessage(new TextComponentString("[mctas] " + msg));
        }
    }
}
