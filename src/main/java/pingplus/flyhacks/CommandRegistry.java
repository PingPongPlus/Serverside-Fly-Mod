package pingplus.flyhacks;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;

public class CommandRegistry {

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, Fly fly) {
        fly.register(dispatcher);
    }
}
