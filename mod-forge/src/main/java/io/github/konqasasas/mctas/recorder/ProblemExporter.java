package io.github.konqasasas.mctas.recorder;

import io.github.konqasasas.mctas.physics.BlockType;
import io.github.konqasasas.mctas.physics.SparseBlockWorld;
import io.github.konqasasas.mctas.physics.VanillaBlocks;
import io.github.konqasasas.mctas.problem.Region;
import io.github.konqasasas.mctas.problem.Spawn;
import io.github.konqasasas.mctas.problem.Vec3;
import io.github.konqasasas.mctas.problem.cli.importer.LegacyBlocks;
import io.github.konqasasas.mctas.problem.cli.importer.ProblemWriter;
import io.github.konqasasas.mctas.problem.io.ProblemIO;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;

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
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code /mctas mark ...} and {@code /mctas export}: picks a box, a spawn, a start and a goal in the game and
 * writes them as a problem file, the same way {@code mctas-problem import} writes one from a save. And
 * {@code /mctas checkids}, which checks the importer's table of numeric ids against this game.
 */
final class ProblemExporter {

    private final Minecraft mc = Minecraft.getMinecraft();
    private BlockPos from;
    private BlockPos to;
    private Spawn spawn;
    private Region start;
    private Region goal;
    /** The block {@code /mctas mark goal} was given, for a landing goal computed at export. */
    private BlockPos goalOn;

    /** The block under the crosshair, or the one the player stands in if none is in reach. */
    BlockPos lookedAt() {
        RayTraceResult hit = mc.objectMouseOver;
        if (hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK) {
            return hit.getBlockPos();
        }
        return new BlockPos(mc.player);
    }

    String markFrom(BlockPos p) {
        from = p;
        return "box corner 1: " + str(p) + box();
    }

    String markTo(BlockPos p) {
        to = p;
        return "box corner 2: " + str(p) + box();
    }

    String markSpawn(EntityPlayer p) {
        spawn = new Spawn(new Vec3(p.posX, p.posY, p.posZ), p.rotationYaw, p.rotationPitch);
        return "spawn: " + p.posX + ", " + p.posY + ", " + p.posZ + " yaw " + p.rotationYaw + " pitch " + p.rotationPitch
            + (p.onGround ? "" : " (you are not on the ground: the spawn should be where you stand still)");
    }

    String markStart(double[] v) {
        start = region(v);
        return "start: " + start;
    }

    String markGoal(double[] v) {
        goal = region(v);
        goalOn = null;
        return "goal: " + goal;
    }

    String markGoalOn(BlockPos p) {
        goalOn = p;
        goal = null;
        return "goal: landing on the block at " + str(p) + " (the region is computed at export)";
    }

    String status() {
        return "box " + (from == null ? "?" : str(from)) + " .. " + (to == null ? "?" : str(to))
            + ", spawn " + (spawn == null ? "?" : spawn.pos.x + "," + spawn.pos.y + "," + spawn.pos.z)
            + ", start " + (start == null ? "?" : start.toString())
            + ", goal " + (goal != null ? goal.toString() : goalOn != null ? "landing on " + str(goalOn) : "?");
    }

    /**
     * Writes {@code .minecraft/mctas/problems/<name>.problem.json}. Returns the messages for the chat: the
     * file, or what is missing or wrong.
     */
    List<String> export(String name, String id) throws IOException {
        List<String> out = new ArrayList<String>();
        if (from == null || to == null || spawn == null || start == null || (goal == null && goalOn == null)) {
            out.add("missing marks: " + status());
            return out;
        }
        World world = mc.world;
        int[] min = {Math.min(from.getX(), to.getX()), Math.max(0, Math.min(from.getY(), to.getY())), Math.min(from.getZ(), to.getZ())};
        int[] max = {Math.max(from.getX(), to.getX()), Math.min(255, Math.max(from.getY(), to.getY())), Math.max(from.getZ(), to.getZ())};
        long volume = (long) (max[0] - min[0] + 1) * (max[1] - min[1] + 1) * (max[2] - min[2] + 1);
        if (volume > 4L * 1024 * 1024) {
            out.add("the box holds " + volume + " blocks; pick a smaller one");
            return out;
        }
        SparseBlockWorld blocks = new SparseBlockWorld();
        Map<String, Integer> unsupported = new TreeMap<String, Integer>();
        boolean unloaded = false;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = min[1]; y <= max[1]; y++) {
            for (int z = min[2]; z <= max[2]; z++) {
                for (int x = min[0]; x <= max[0]; x++) {
                    pos.setPos(x, y, z);
                    if (!world.isBlockLoaded(pos)) {
                        unloaded = true;
                        continue;
                    }
                    IBlockState state = world.getBlockState(pos);
                    if (state.getBlock() == net.minecraft.init.Blocks.AIR) {
                        continue;
                    }
                    state = state.getActualState(world, pos);
                    String catalog = ClientWorldBlocks.catalogName(state);
                    BlockType t = catalog == null ? null : VanillaBlocks.get(catalog);
                    if (t == null) {
                        String key = String.valueOf(Block.REGISTRY.getNameForObject(state.getBlock()));
                        unsupported.put(key, unsupported.containsKey(key) ? unsupported.get(key) + 1 : 1);
                        continue;
                    }
                    if (!t.isAir()) {
                        blocks.setBlock(x, y, z, t);
                    }
                }
            }
        }
        if (unloaded) {
            out.add("part of the box is in chunks that are not loaded; come closer and export again");
            return out;
        }
        if (!unsupported.isEmpty()) {
            out.add("the box holds blocks the engine does not support: " + unsupported);
            return out;
        }
        Region g = goal;
        if (g == null) {
            g = ProblemWriter.landingOn(blocks, goalOn.getX(), goalOn.getY(), goalOn.getZ());
            if (g == null) {
                out.add("the goal block at " + str(goalOn) + " has no collision box (or is outside the box)");
                return out;
            }
        }
        if (start.intersects(g)) {
            out.add("the start and the goal overlap");
            return out;
        }
        List<String> warnings = new ArrayList<String>(ProblemWriter.blockWarnings(blocks));
        warnings.addAll(ProblemWriter.checkSpawn(blocks, spawn));
        for (String w : warnings) {
            if (w.startsWith("error:")) {
                out.add(w);
                return out;
            }
        }
        String imported = "{\"exportedFrom\": \"mctas-recorder\", \"from\": [" + min[0] + ", " + min[1] + ", " + min[2]
            + "], \"to\": [" + max[0] + ", " + max[1] + ", " + max[2] + "]}";
        String json = ProblemWriter.problemJson(id, null, blocks, imported, spawn, start, g,
            ProblemIO.DEFAULT_MAX_TICKS, min[1]);
        ProblemIO.parse(json);
        File dir = new File(mc.gameDir, "mctas/problems");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        File file = new File(dir, name.endsWith(".problem.json") ? name : name + ".problem.json");
        PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
        try {
            w.print(json);
        } finally {
            w.close();
        }
        out.addAll(warnings);
        out.add("wrote " + file.getPath() + " (" + blocks.size() + " blocks)");
        return out;
    }

    /**
     * Compares {@code LegacyBlocks} with this game for every id and metadata: the state a chunk loads
     * ({@code Block.BLOCK_STATE_IDS}), in the catalog's names. Writes every difference to a log and returns
     * the chat summary.
     */
    String checkIds() throws IOException {
        File dir = new File(mc.gameDir, "mctas/logs");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        File file = new File(dir, "checkids-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".txt");
        int pairs = 0;
        int differ = 0;
        PrintWriter log = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
        try {
            log.println("# id:meta, game, importer (only the differences)");
            for (int id = 0; id < 256; id++) {
                for (int meta = 0; meta < 16; meta++) {
                    IBlockState s = Block.BLOCK_STATE_IDS.getByValue(id << 4 | meta);
                    String game = s == null ? "minecraft:air" : gameName(s);
                    String ours = ourName(id, meta);
                    if (s != null || !ours.equals("minecraft:air")) {
                        pairs++;
                    }
                    if (!game.equals(ours)) {
                        differ++;
                        log.println(id + ":" + meta + "\t" + game + "\t" + ours);
                    }
                }
            }
            log.println("# " + pairs + " pairs, " + differ + " differ");
        } finally {
            log.close();
        }
        return "checkids: " + pairs + " id:meta pairs, " + differ + " differ. Send " + file.getPath();
    }

    private static String gameName(IBlockState s) {
        String id = String.valueOf(Block.REGISTRY.getNameForObject(s.getBlock()));
        String catalog = ClientWorldBlocks.catalogName(s);
        return comparable(catalog == null ? id : catalog);
    }

    private static String ourName(int id, int meta) {
        if (LegacyBlocks.name(id) == null) {
            return "minecraft:air";
        }
        if (!LegacyBlocks.stored(id, meta)) {
            return "minecraft:air";
        }
        try {
            return comparable(LegacyBlocks.state(id, meta));
        } catch (IllegalArgumentException e) {
            return "error: " + e.getMessage();
        }
    }

    /**
     * The name with the keys sorted, and for a door half without what the half does not store (a lower
     * half's hinge, an upper half's facing and open), which only {@code getActualState} decides.
     */
    private static String comparable(String name) {
        String id = VanillaBlocks.blockId(name);
        Map<String, String> p = new TreeMap<String, String>(VanillaBlocks.parseProperties(name));
        if ("lower".equals(p.get("half")) && p.containsKey("hinge")) {
            p.remove("hinge");
        } else if ("upper".equals(p.get("half")) && p.containsKey("hinge")) {
            p.remove("facing");
            p.remove("open");
        }
        return VanillaBlocks.name(id, p);
    }

    private static Region region(double[] v) {
        return new Region(Math.min(v[0], v[3]), Math.min(v[1], v[4]), Math.min(v[2], v[5]),
            Math.max(v[0], v[3]), Math.max(v[1], v[4]), Math.max(v[2], v[5]));
    }

    private String box() {
        if (from == null || to == null) {
            return "";
        }
        long n = (long) (Math.abs(from.getX() - to.getX()) + 1) * (Math.abs(from.getY() - to.getY()) + 1)
            * (Math.abs(from.getZ() - to.getZ()) + 1);
        return " (box of " + n + " blocks)";
    }

    private static String str(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }
}
