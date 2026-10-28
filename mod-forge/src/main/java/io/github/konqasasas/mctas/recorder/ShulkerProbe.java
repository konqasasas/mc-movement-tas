package io.github.konqasasas.mctas.recorder;

import io.github.konqasasas.mctas.physics.EngineBuild;
import io.github.konqasasas.mctas.physics.PlayerInput;
import net.minecraft.block.BlockShulkerBox;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityShulkerBox;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;

/**
 * {@code /mctas probe shulker [trials]}: measures when a shulker box the player right-clicks starts to
 * open on the client, when its screen opens, and when the lid starts to close after the screen is
 * closed, in client ticks after the click. The box opens on the server, which tells the client with a
 * block event; how many client ticks that takes is not fixed, and the engine needs its range. Each
 * trial also logs the player's position every tick, so the lift by the lid (and any correction the
 * server sends back) can be compared with the engine later.
 *
 * <p>Trials alternate between a box facing up with the player standing on it, looking straight down,
 * and a box facing east with the player standing east of it, looking at its side. The area around the
 * player (7 by 7 blocks, 4 high) is rebuilt for each trial.
 */
final class ShulkerProbe {

    private enum Phase { IDLE, BUILD, SETTLE, WATCH }

    private static final int SETTLE_TICKS = 15;
    private static final int WATCH_TIMEOUT = 120;
    /** Ticks to keep logging after the lid is closed again. */
    private static final int AFTER_CLOSED = 10;

    private final Minecraft mc = Minecraft.getMinecraft();
    private final InputDriver driver = new InputDriver();
    private final Random random = new Random(1L);

    private Phase phase = Phase.IDLE;
    private volatile boolean serverReady;
    private PrintWriter log;
    private File logFile;
    private int trials;
    private int trial;
    private int ox;
    private int by;
    private int oz;
    private BlockPos box;
    private boolean up;
    private float yaw;
    private float pitch;
    private int settle;
    /** Ticks to wait after settling before the click, to click at different points of the server's tick. */
    private int delay;
    /** Ticks since the click: 0 is the tick of the click. */
    private int k;
    private int hold;
    private int lidOpen;
    private int screenOpen;
    private int closeAt;
    private int lidClose;
    private int closedAt;
    private double lastX;
    private double lastY;
    private double lastZ;
    private final List<int[]> results = new ArrayList<int[]>();

    boolean active() {
        return phase != Phase.IDLE;
    }

    void start(int n) throws IOException {
        EntityPlayerSP p = mc.player;
        if (phase != Phase.IDLE || p == null) {
            return;
        }
        if (mc.getIntegratedServer() == null) {
            chat("probe needs a single-player world.");
            return;
        }
        if (!mc.playerController.isNotCreative() || p.capabilities.allowFlying) {
            chat("Switch to survival mode first.");
            return;
        }
        File dir = new File(mc.gameDir, "mctas/logs");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        logFile = new File(dir, "probe-shulker-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".txt");
        log = new PrintWriter(new OutputStreamWriter(new FileOutputStream(logFile), StandardCharsets.UTF_8));
        log.println("# mctas shulker probe, Minecraft 1.12.2, engine build " + EngineBuild.ID);
        log.println("# k is client ticks since the click (0 = the tick of the click); 'start' is read at the start"
                + " of the tick after the client took in the server's packets, 'end' after the tick ran");
        log.println("# k phase status progress screen x y z motionX motionY motionZ onGround");
        trials = n;
        trial = 0;
        results.clear();
        ox = (int) Math.floor(p.posX);
        by = (int) Math.floor(p.posY);
        oz = (int) Math.floor(p.posZ);
        driver.install(p);
        chat("Shulker probe: " + n + " trials at " + ox + " " + by + " " + oz + ". Do not touch the game window.");
        nextTrial();
    }

    void stop(String why) {
        if (phase == Phase.IDLE) {
            return;
        }
        phase = Phase.IDLE;
        if (mc.player != null && mc.currentScreen != null) {
            mc.player.closeScreen();
        }
        driver.uninstall();
        String summary = summary();
        log.println("# " + summary + (why == null ? "" : " (" + why + ")"));
        log.close();
        log = null;
        chat(summary + ". Log: " + logFile.getPath());
    }

    private void nextTrial() {
        if (trial >= trials) {
            stop(null);
            return;
        }
        up = trial % 2 == 0;
        trial++;
        box = new BlockPos(ox, by, oz);
        final double px = up ? ox + 0.5D : ox + 1.5D;
        final double py = up ? by + 1 : by;
        final double pz = oz + 0.5D;
        yaw = up ? 0.0F : 90.0F;
        pitch = up ? 90.0F : 60.0F;
        delay = random.nextInt(4);
        hold = 3 + random.nextInt(6);
        k = -1;
        lidOpen = screenOpen = closeAt = lidClose = closedAt = -1;
        log.println("# trial " + trial + ": box at " + ox + " " + by + " " + oz + " facing " + (up ? "up" : "east")
                + ", player at " + px + " " + py + " " + pz + ", click after " + delay + " more ticks, screen held "
                + hold + " ticks");
        phase = Phase.BUILD;
        serverReady = false;
        final IntegratedServer server = mc.getIntegratedServer();
        final java.util.UUID id = mc.player.getUniqueID();
        final boolean faceUp = up;
        final float y = yaw;
        final float pt = pitch;
        server.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                WorldServer ws = server.getWorld(0);
                for (int x = ox - 3; x <= ox + 3; x++) {
                    for (int z = oz - 3; z <= oz + 3; z++) {
                        ws.setBlockState(new BlockPos(x, by - 1, z), Blocks.STONE.getDefaultState(), 2);
                        for (int yy = by; yy <= by + 3; yy++) {
                            ws.setBlockState(new BlockPos(x, yy, z), Blocks.AIR.getDefaultState(), 2);
                        }
                    }
                }
                IBlockState s = Blocks.PURPLE_SHULKER_BOX.getDefaultState()
                        .withProperty(BlockShulkerBox.FACING, faceUp ? EnumFacing.UP : EnumFacing.EAST);
                ws.setBlockState(new BlockPos(ox, by, oz), s, 2);
                EntityPlayerMP mp = server.getPlayerList().getPlayerByUUID(id);
                if (mp != null) {
                    mp.fallDistance = 0.0F;
                    mp.connection.setPlayerLocation(px, py, pz, y, pt);
                }
                serverReady = true;
            }
        });
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
            driver.apply(new PlayerInput(0, yaw, pitch));
            return;
        }
        if (phase != Phase.WATCH) {
            return;
        }
        k++;
        if (p.posX != lastX || p.posY != lastY || p.posZ != lastZ) {
            log.println("# between ticks " + (k - 1) + " and " + k + ": the position changed outside the player tick");
        }
        TileEntityShulkerBox te = tile();
        String status = te == null ? "NONE" : te.getAnimationStatus().name();
        boolean screen = mc.currentScreen instanceof net.minecraft.client.gui.inventory.GuiShulkerBox;
        if (lidOpen < 0 && te != null && te.getAnimationStatus() != TileEntityShulkerBox.AnimationStatus.CLOSED) {
            lidOpen = k;
        }
        if (screenOpen < 0 && screen) {
            screenOpen = k;
        }
        if (closeAt >= 0 && lidClose < 0 && te != null && te.getAnimationStatus() == TileEntityShulkerBox.AnimationStatus.CLOSING) {
            lidClose = k;
        }
        line("start", p, te);
        if (screenOpen >= 0 && closeAt < 0 && k - screenOpen >= hold) {
            closeAt = k;
            log.println("# closing the screen at the start of tick " + k);
            p.closeScreen();
        }
        driver.apply(new PlayerInput(k == 0 ? PlayerInput.USE : 0, yaw, pitch));
    }

    private void onTickEnd(EntityPlayerSP p) {
        switch (phase) {
            case BUILD:
                if (serverReady) {
                    phase = Phase.SETTLE;
                    settle = 0;
                }
                return;
            case SETTLE:
                settle++;
                boolean built = mc.world.getBlockState(box).getBlock() instanceof BlockShulkerBox;
                if (settle >= SETTLE_TICKS + delay && p.onGround && built) {
                    phase = Phase.WATCH;
                    lastX = p.posX;
                    lastY = p.posY;
                    lastZ = p.posZ;
                } else if (settle > 100) {
                    log.println("# the player did not settle, trial skipped");
                    nextTrial();
                }
                return;
            case WATCH:
                TileEntityShulkerBox te = tile();
                line("end", p, te);
                lastX = p.posX;
                lastY = p.posY;
                lastZ = p.posZ;
                if (closedAt < 0 && lidClose >= 0 && te != null
                        && te.getAnimationStatus() == TileEntityShulkerBox.AnimationStatus.CLOSED) {
                    closedAt = k;
                }
                boolean done = closedAt >= 0 && k - closedAt >= AFTER_CLOSED;
                if (done || k >= WATCH_TIMEOUT) {
                    log.println("# trial " + trial + " " + (up ? "up" : "east") + ": lid opening seen at the start of tick "
                            + lidOpen + ", screen open at " + screenOpen + ", screen closed at " + closeAt
                            + ", lid closing seen at " + lidClose + (done ? "" : " (timed out)"));
                    log.flush();
                    results.add(new int[] {up ? 1 : 0, lidOpen, screenOpen, closeAt < 0 || lidClose < 0 ? -1 : lidClose - closeAt});
                    if (mc.currentScreen != null) {
                        p.closeScreen();
                    }
                    nextTrial();
                }
                return;
            default:
        }
    }

    private TileEntityShulkerBox tile() {
        TileEntity te = mc.world.getTileEntity(box);
        return te instanceof TileEntityShulkerBox ? (TileEntityShulkerBox) te : null;
    }

    private void line(String when, EntityPlayerSP p, TileEntityShulkerBox te) {
        log.println(k + " " + when + " " + (te == null ? "NONE -" : te.getAnimationStatus().name() + " "
                + Float.toHexString(te.getProgress(1.0F))) + " "
                + (mc.currentScreen == null ? "-" : mc.currentScreen.getClass().getSimpleName()) + " "
                + Double.toHexString(p.posX) + " " + Double.toHexString(p.posY) + " " + Double.toHexString(p.posZ) + " "
                + Double.toHexString(p.motionX) + " " + Double.toHexString(p.motionY) + " " + Double.toHexString(p.motionZ)
                + " " + p.onGround);
    }

    /** "up: lid opens 2..3, screen 2..3, lid closes 1..2 ticks after closing; east: ...". */
    private String summary() {
        StringBuilder sb = new StringBuilder("shulker probe");
        for (int face = 1; face >= 0; face--) {
            int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
            int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            int n = 0;
            int missing = 0;
            for (int[] r : results) {
                if (r[0] != face) {
                    continue;
                }
                n++;
                for (int i = 0; i < 3; i++) {
                    if (r[i + 1] < 0) {
                        missing++;
                        continue;
                    }
                    lo[i] = Math.min(lo[i], r[i + 1]);
                    hi[i] = Math.max(hi[i], r[i + 1]);
                }
            }
            if (n == 0) {
                continue;
            }
            sb.append(face == 1 ? "; up (" : "; east (").append(n).append(" trials): lid opens ")
                    .append(range(lo[0], hi[0])).append(", screen opens ").append(range(lo[1], hi[1]))
                    .append(" ticks after the click, lid closes ").append(range(lo[2], hi[2]))
                    .append(" ticks after closing the screen");
            if (missing > 0) {
                sb.append(", ").append(missing).append(" not seen");
            }
        }
        return sb.toString();
    }

    private static String range(int lo, int hi) {
        return lo == Integer.MAX_VALUE ? "-" : lo + ".." + hi;
    }

    private void chat(String msg) {
        if (mc.player != null) {
            mc.player.sendMessage(new TextComponentString("[mctas] " + msg));
        }
    }
}
