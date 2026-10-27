package io.github.konqasasas.mctas.recorder;

import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;

import java.io.File;
import java.io.IOException;

/**
 * {@code /mctas fuzz [shapes] [runs] [ticks] [firstSeed]}, {@code /mctas script <file>}, {@code /mctas record},
 * {@code /mctas stop}. Script files are read from {@code .minecraft/mctas/scripts}.
 */
final class McTasCommand extends CommandBase {

    private final RecorderController controller;

    McTasCommand(RecorderController controller) {
        this.controller = controller;
    }

    @Override
    public String getName() {
        return "mctas";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/mctas fuzz [shapes] [runs] [ticks] [firstSeed] | script <file> | record | stop";
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
                boolean shapes = args.length > 1 && args[1].equals("shapes");
                int i = shapes ? 2 : 1;
                int runs = args.length > i ? parseInt(args[i], 1) : 100;
                int ticks = args.length > i + 1 ? parseInt(args[i + 1], 1) : 200;
                long first = args.length > i + 2 ? parseLong(args[i + 2]) : 1L;
                controller.startFuzz(runs, ticks, first, shapes);
            } else if (sub.equals("script") && args.length > 1) {
                File dir = new File(Minecraft.getMinecraft().gameDir, "mctas/scripts");
                File f = new File(dir, args[1]);
                if (!f.isFile()) {
                    f = new File(dir, args[1] + ".txt");
                }
                controller.startScript(f);
            } else if (sub.equals("record")) {
                controller.startRecord();
            } else if (sub.equals("stop")) {
                controller.stop("stopped by command");
            } else {
                sender.sendMessage(new TextComponentString(getUsage(sender)));
            }
        } catch (IOException e) {
            sender.sendMessage(new TextComponentString("[mctas] " + e.getMessage()));
        }
    }
}
