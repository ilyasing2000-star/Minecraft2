package me.macro.blockhit;

import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

import org.lwjgl.input.Keyboard;

@Mod(
        modid = AutoBlockHit.MODID,
        name = AutoBlockHit.NAME,
        version = AutoBlockHit.VERSION,
        clientSideOnly = true,
        acceptedMinecraftVersions = "[1.8.9]"
)
public class AutoBlockHit {

    public static final String MODID = "autoblockhit";
    public static final String NAME = "Auto BlockHit";
    public static final String VERSION = "1.0";

    @Mod.Instance(MODID)
    public static AutoBlockHit instance;

    public static KeyBinding keyToggle;
    public static BlockHitHandler handler;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        BlockHitConfig.load(event.getSuggestedConfigurationFile());
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        handler = new BlockHitHandler();

        // В 1.8.9 ещё две разные шины: MouseEvent приходит на forge-шину,
        // а ClientTickEvent на fml-шину. Регистрируемся на обеих.
        MinecraftForge.EVENT_BUS.register(handler);
        FMLCommonHandler.instance().bus().register(handler);

        keyToggle = new KeyBinding("key.autoblockhit.toggle", Keyboard.KEY_B, "Auto BlockHit");
        ClientRegistry.registerKeyBinding(keyToggle);

        ClientCommandHandler.instance.registerCommand(new BlockHitCommand());
    }
}
