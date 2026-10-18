package pingplus.flyhacks;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.GameType;

public final class Fly {
    private static final SimpleCommandExceptionType OPERATORS_ONLY = new SimpleCommandExceptionType(
            Component.literal("Flight is restricted to operators."));
    private static final SimpleCommandExceptionType NOT_ENABLED = new SimpleCommandExceptionType(
            Component.literal("Enable flight with /fly before setting its speed."));
    private final Map<UUID, FlightState> players = new HashMap<>();
    private final FlyConfig config;

    Fly(FlyConfig config) {
        this.config = config;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("fly")
                .requires(this::canUse)
                .executes(context -> toggleFly(context.getSource()))
                .then(Commands.argument("speed", FloatArgumentType.floatArg(0.1f, 100.0f))
                        .executes(context -> setFlySpeed(context.getSource(),
                                FloatArgumentType.getFloat(context, "speed"))))
                .then(Commands.literal("operatorsOnly")
                        .requires(Fly::isOperator)
                        .executes(context -> {
                            context.getSource().sendSuccess(() -> Component.literal(
                                    "Operators-only flight: " + config.operatorsOnly()), false);
                            return 1;
                        })
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                .executes(context -> setOperatorsOnly(context.getSource(),
                                        BoolArgumentType.getBool(context, "enabled"))))));
    }

    private static boolean isOperator(CommandSourceStack source) {
        return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    private boolean canUse(CommandSourceStack source) {
        return !config.operatorsOnly() || isOperator(source);
    }

    private void requireAccess(CommandSourceStack source) throws CommandSyntaxException {
        if (!canUse(source)) {
            throw OPERATORS_ONLY.create();
        }
    }

    private int toggleFly(CommandSourceStack source) throws CommandSyntaxException {
        requireAccess(source);
        ServerPlayer player = source.getPlayerOrException();
        if (players.containsKey(player.getUUID())) {
            disable(player);
            source.sendSuccess(() -> Component.literal("Flying disabled!"), false);
        } else {
            Abilities abilities = player.getAbilities();
            players.put(player.getUUID(), new FlightState(player.gameMode(), abilities.mayfly,
                    abilities.getFlyingSpeed()));
            abilities.mayfly = true;
            abilities.flying = true;
            player.resetFallDistance();
            player.onUpdateAbilities();
            source.sendSuccess(() -> Component.literal("Flying enabled!"), false);
        }
        return 1;
    }

    private int setFlySpeed(CommandSourceStack source, float speed) throws CommandSyntaxException {
        requireAccess(source);
        ServerPlayer player = source.getPlayerOrException();
        if (!players.containsKey(player.getUUID())) {
            throw NOT_ENABLED.create();
        }
        // User-facing values are multiples of vanilla's default flight speed.
        player.getAbilities().setFlyingSpeed(0.05f * speed);
        player.onUpdateAbilities();
        source.sendSuccess(() -> Component.literal("Flying speed set to " + speed + "x!"), false);
        return 1;
    }

    private int setOperatorsOnly(CommandSourceStack source, boolean enabled) throws CommandSyntaxException {
        if (!isOperator(source)) {
            throw OPERATORS_ONLY.create();
        }
        try {
            config.setOperatorsOnly(enabled);
        } catch (IOException exception) {
            FlyMod.LOGGER.error("Could not save Fly Mod configuration", exception);
            source.sendFailure(Component.literal("Could not save flight access settings. Check the server log."));
            return 0;
        }
        MinecraftServer server = source.getServer();
        tick(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            server.getCommands().sendCommands(player);
        }
        source.sendSuccess(() -> Component.literal(enabled
                ? "Flight is now restricted to operators."
                : "Flight is now available to everyone."), true);
        return 1;
    }

    void disable(ServerPlayer player) {
        FlightState previous = players.remove(player.getUUID());
        if (previous == null) {
            return;
        }
        Abilities abilities = player.getAbilities();
        boolean nativeFlight = player.isCreative() || player.isSpectator();
        abilities.mayfly = nativeFlight || (player.gameMode() == previous.gameMode() && previous.allowedBefore());
        abilities.flying = player.isSpectator();
        abilities.setFlyingSpeed(previous.speedBefore());
        player.resetFallDistance();
        player.onUpdateAbilities();
    }

    void tick(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            FlightState state = players.get(player.getUUID());
            if (state != null && (state.gameMode() != player.gameMode()
                    || !canUse(player.createCommandSourceStack()))) {
                disable(player);
            }
        }
    }

    void stop(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            disable(player);
        }
        players.clear();
    }

    private record FlightState(GameType gameMode, boolean allowedBefore, float speedBefore) {
    }
}
