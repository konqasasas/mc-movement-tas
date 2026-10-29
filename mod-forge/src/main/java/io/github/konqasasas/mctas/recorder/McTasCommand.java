package io.github.konqasasas.mctas.recorder;

import io.github.konqasasas.mctas.physics.testing.RandomScenario;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentString;

import java.io.File;
import java.io.IOException;

/**
 * {@code /mctas fuzz [shapes] [runs] [ticks] [firstSeed]}, {@code /mctas script <file>}, {@code /mctas record},
 * {@code /mctas replay <problem> <solution> [force]}, {@code /mctas stop}, {@code /mctas mark ...},
 * {@code /mctas export <name> [id]}, {@code /mctas checkids}. Script files are read from
 * {@code .minecraft/mctas/scripts}; replay paths are absolute or relative to {@code .minecraft/mctas}.
 */
final class McTasCommand extends CommandBase {

    private final RecorderController controller;
    private final ShulkerProbe probe;
    private final ProblemExporter exporter = new ProblemExporter();

    McTasCommand(RecorderController controller, ShulkerProbe probe) {
        this.controller = controller;
        this.probe = probe;
    }

    @Override
    public String getName() {
        return "mctas";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/mctas fuzz [shapes|fluids|place|press|shulker|plate] [runs] [ticks] [firstSeed] | script <file> | replay <problem> <solution> [force] | record | probe shulker [trials] | stop"
                + " | mark from|to [x y z] | mark spawn | mark start x1 y1 z1 x2 y2 z2 | mark goal [x y z | x1 y1 z1 x2 y2 z2] | export <name> [id] | checkids";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
        return true;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        String sub = args.length == 0 ? "" : args[0];
        try {
            if (sub.equals("fuzz")) {
                boolean named = args.length > 1 && java.util.Arrays.asList(RandomScenario.COURSES).contains(args[1]);
                String course = named ? args[1] : "full";
                int i = named ? 2 : 1;
                int runs = args.length > i ? parseInt(args[i], 1) : 100;
                int ticks = args.length > i + 1 ? parseInt(args[i + 1], 1) : 200;
                long first = args.length > i + 2 ? parseLong(args[i + 2]) : 1L;
                controller.startFuzz(runs, ticks, first, course);
            } else if (sub.equals("script") && args.length > 1) {
                File dir = new File(Minecraft.getMinecraft().gameDir, "mctas/scripts");
                File f = new File(dir, args[1]);
                if (!f.isFile()) {
                    f = new File(dir, args[1] + ".txt");
                }
                controller.startScript(f);
            } else if (sub.equals("replay") && args.length > 2) {
                File dir = new File(Minecraft.getMinecraft().gameDir, "mctas");
                controller.startReplay(resolve(dir, args[1]), resolve(dir, args[2]),
                        args.length > 3 && args[3].equals("force"));
            } else if (sub.equals("record")) {
                controller.startRecord();
            } else if (sub.equals("probe") && args.length > 1 && args[1].equals("shulker")) {
                if (controller.active()) {
                    say(sender, "Already running. /mctas stop first.");
                } else {
                    probe.start(args.length > 2 ? parseInt(args[2], 1) : 20);
                }
            } else if (sub.equals("stop")) {
                controller.stop("stopped by command");
                probe.stop("stopped by command");
            } else if (sub.equals("mark")) {
                say(sender, mark(sender, args));
            } else if (sub.equals("export") && args.length > 1) {
                for (String line : exporter.export(args[1], args.length > 2 ? args[2] : args[1])) {
                    say(sender, line);
                }
            } else if (sub.equals("checkids")) {
                say(sender, exporter.checkIds());
            } else {
                sender.sendMessage(new TextComponentString(getUsage(sender)));
            }
        } catch (IOException e) {
            sender.sendMessage(new TextComponentString("[mctas] " + e.getMessage()));
        }
    }

    private String mark(ICommandSender sender, String[] args) throws CommandException {
        String what = args.length > 1 ? args[1] : "";
        int n = args.length - 2;
        if ((what.equals("from") || what.equals("to")) && (n == 0 || n == 3)) {
            BlockPos p = n == 0 ? exporter.lookedAt() : parseBlockPos(sender, args, 2, false);
            return what.equals("from") ? exporter.markFrom(p) : exporter.markTo(p);
        } else if (what.equals("spawn") && n == 0) {
            return exporter.markSpawn(Minecraft.getMinecraft().player);
        } else if (what.equals("start") && n == 6) {
            return exporter.markStart(doubles(sender, args));
        } else if (what.equals("goal") && n == 6) {
            return exporter.markGoal(doubles(sender, args));
        } else if (what.equals("goal") && (n == 0 || n == 3)) {
            return exporter.markGoalOn(n == 0 ? exporter.lookedAt() : parseBlockPos(sender, args, 2, false));
        } else if (what.isEmpty()) {
            return exporter.status();
        }
        return "usage: /mctas mark from|to [x y z] | spawn | start x1 y1 z1 x2 y2 z2 | goal [x y z | x1 y1 z1 x2 y2 z2]";
    }

    /** Two corners from args[2..7], {@code ~} relative to the player. */
    private static double[] doubles(ICommandSender sender, String[] args) throws CommandException {
        Vec3d at = sender.getPositionVector();
        double[] base = {at.x, at.y, at.z};
        double[] v = new double[6];
        for (int i = 0; i < 6; i++) {
            v[i] = parseDouble(base[i % 3], args[2 + i], false);
        }
        return v;
    }

    private static void say(ICommandSender sender, String msg) {
        sender.sendMessage(new TextComponentString("[mctas] " + msg));
    }

    private static File resolve(File dir, String path) throws IOException {
        File f = new File(path);
        if (!f.isAbsolute()) {
            f = new File(dir, path);
        }
        if (!f.isFile()) {
            throw new IOException("no file " + f.getPath());
        }
        return f;
    }
}
