package io.github.konqasasas.mctas.recorder;

import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;

@Mod(modid = McTasRecorderMod.MODID, name = "MC TAS Recorder", clientSideOnly = true,
        acceptedMinecraftVersions = "[1.12.2]", acceptableRemoteVersions = "*")
public class McTasRecorderMod {

    public static final String MODID = "mctasrecorder";

    private final RecorderController controller = new RecorderController();

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(controller);
        ClientCommandHandler.instance.registerCommand(new McTasCommand(controller));
    }
}
