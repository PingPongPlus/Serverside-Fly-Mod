package pingplus.flyhacks;

import java.io.IOException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FlyMod implements ModInitializer {
    static final Logger LOGGER = LoggerFactory.getLogger("flymod");

    @Override
    public void onInitialize() {
        final Fly fly;
        try {
            fly = new Fly(new FlyConfig(FabricLoader.getInstance().getConfigDir().resolve("flymod.properties")));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load Fly Mod configuration", exception);
        }
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            CommandRegistry.registerCommands(dispatcher, fly);
        });
        ServerPlayerEvents.LEAVE.register(fly::disable);
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> fly.disable(newPlayer));
        ServerTickEvents.END_SERVER_TICK.register(fly::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(fly::stop);
    }
}
