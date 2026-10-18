package pingplus.flyhacks;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;

public class FlyGameTests {
    @GameTest
    public void multiplayerFlightIsIndependent(GameTestHelper helper) throws CommandSyntaxException {
        ServerPlayer alice = player(helper, GameType.SURVIVAL);
        ServerPlayer bob = player(helper, GameType.SURVIVAL);
        execute(alice, "fly");
        execute(bob, "fly");
        helper.assertTrue(alice.getAbilities().flying && bob.getAbilities().flying,
                "Both players must be able to enable flight independently");
        execute(bob, "fly");
        helper.assertTrue(alice.getAbilities().flying, "Bob's toggle must not affect Alice");
        helper.assertFalse(bob.getAbilities().mayfly, "Bob must lose survival flight when disabled");
        helper.succeed();
    }

    @GameTest
    public void speedRequiresOwnFlight(GameTestHelper helper) throws CommandSyntaxException {
        ServerPlayer alice = player(helper, GameType.SURVIVAL);
        ServerPlayer bob = player(helper, GameType.SURVIVAL);
        execute(alice, "fly");
        rejects(helper, bob.createCommandSourceStack(), "fly 2");
        helper.assertValueEqual(bob.getAbilities().getFlyingSpeed(), 0.05f,
                "Another player's flight must not unlock speed changes");
        helper.succeed();
    }

    @GameTest
    public void creativeFlightSurvivesDisable(GameTestHelper helper) throws CommandSyntaxException {
        ServerPlayer player = player(helper, GameType.CREATIVE);
        execute(player, "fly");
        execute(player, "fly");
        helper.assertTrue(player.getAbilities().mayfly, "Creative players must retain native flight");
        helper.assertFalse(player.getAbilities().flying, "Creative players can stop flying");
        helper.succeed();
    }

    @GameTest
    public void spectatorFlightSurvivesDisable(GameTestHelper helper) throws CommandSyntaxException {
        ServerPlayer player = player(helper, GameType.SPECTATOR);
        execute(player, "fly");
        execute(player, "fly");
        helper.assertTrue(player.getAbilities().mayfly && player.getAbilities().flying,
                "Spectators must keep flying");
        helper.succeed();
    }

    @GameTest
    public void speedIsBoundedAndRestored(GameTestHelper helper) throws CommandSyntaxException {
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        player.getAbilities().setFlyingSpeed(0.075f);
        execute(player, "fly");
        execute(player, "fly 2");
        helper.assertValueEqual(player.getAbilities().getFlyingSpeed(), 0.1f, "Speed 2 means twice vanilla speed");
        rejects(helper, player.createCommandSourceStack(), "fly 0");
        rejects(helper, player.createCommandSourceStack(), "fly 101");
        execute(player, "fly");
        helper.assertValueEqual(player.getAbilities().getFlyingSpeed(), 0.075f, "Disabling must restore the previous speed");
        helper.succeed();
    }

    @GameTest
    public void onlyOperatorsCanChangeAccess(GameTestHelper helper) throws CommandSyntaxException {
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        ServerPlayer operatorPlayer = player(helper, GameType.SURVIVAL);
        MinecraftServer server = helper.getLevel().getServer();
        NameAndId operatorId = new NameAndId(operatorPlayer.getGameProfile());
        // The GameTest server defaults new operators to level 0; explicitly grant level 2.
        server.getPlayerList().op(operatorId, Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.empty());
        CommandSourceStack ordinary = player.createCommandSourceStack().withPermission(LevelBasedPermissionSet.ALL);
        CommandSourceStack operator = operatorPlayer.createCommandSourceStack();
        rejects(helper, ordinary, "fly operatorsOnly true");
        execute(ordinary, "fly");
        try {
            execute(operator, "fly operatorsOnly true");
            helper.assertFalse(player.getAbilities().mayfly, "Restricting access must revoke active non-operator flight");
            rejects(helper, ordinary, "fly");
            rejects(helper, ordinary, "fly 2");
            rejects(helper, ordinary, "fly operatorsOnly false");
            execute(operator, "fly");
            ServerTickEvents.END_SERVER_TICK.invoker().onEndTick(server);
            helper.assertTrue(operatorPlayer.getAbilities().flying, "Operators retain access");
            server.getPlayerList().deop(operatorId);
            ServerTickEvents.END_SERVER_TICK.invoker().onEndTick(server);
            helper.assertFalse(operatorPlayer.getAbilities().mayfly, "Losing operator status must revoke restricted flight");
        } finally {
            execute(server.createCommandSourceStack(), "fly operatorsOnly false");
            server.getPlayerList().deop(operatorId);
        }
        execute(ordinary, "fly");
        helper.assertTrue(player.getAbilities().flying, "Public access can be restored without a restart");
        helper.succeed();
    }

    @GameTest
    public void gameModeChangesClearTheToggle(GameTestHelper helper) throws CommandSyntaxException {
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        execute(player, "fly");
        execute(player, "fly 3");
        player.setGameMode(GameType.CREATIVE);
        ServerTickEvents.END_SERVER_TICK.invoker().onEndTick(helper.getLevel().getServer());
        helper.assertTrue(player.getAbilities().mayfly, "Changing to Creative retains native flight");
        helper.assertValueEqual(player.getAbilities().getFlyingSpeed(), 0.05f, "Game mode changes restore speed");
        player.setGameMode(GameType.SURVIVAL);
        execute(player, "fly");
        helper.assertTrue(player.getAbilities().flying, "The next toggle must enable flight after a game mode change");
        helper.succeed();
    }

    @GameTest
    public void respawnClearsFlightAndSpeed(GameTestHelper helper) throws CommandSyntaxException {
        ServerPlayer original = player(helper, GameType.SURVIVAL);
        execute(original, "fly");
        execute(original, "fly 3");
        ServerPlayer respawned = helper.getLevel().getServer().getPlayerList()
                .respawn(original, false, Entity.RemovalReason.KILLED);
        cleanup(helper, respawned);
        helper.assertFalse(respawned.getAbilities().mayfly, "Respawning must clear survival flight");
        helper.assertValueEqual(respawned.getAbilities().getFlyingSpeed(), 0.05f, "Respawning must restore speed");
        execute(respawned, "fly");
        helper.assertTrue(respawned.getAbilities().flying, "The first toggle after respawn must enable flight");
        helper.succeed();
    }

    @GameTest
    public void disconnectDoesNotLeaveStaleFlight(GameTestHelper helper) throws CommandSyntaxException {
        ServerPlayer original = player(helper, GameType.SURVIVAL);
        execute(original, "fly");
        execute(original, "fly 3");
        helper.getLevel().getServer().getPlayerList().remove(original);
        helper.assertFalse(original.getAbilities().mayfly, "Disconnect must clear flight before player data is saved");
        helper.assertValueEqual(original.getAbilities().getFlyingSpeed(), 0.05f, "Disconnect must restore speed");
        ServerPlayer reconnected = player(helper, GameType.SURVIVAL, original.getGameProfile());
        execute(reconnected, "fly");
        helper.assertTrue(reconnected.getAbilities().flying, "The first toggle after reconnect must enable flight");
        helper.succeed();
    }

    @GameTest
    public void consoleFlightReportsAnError(GameTestHelper helper) {
        CommandSourceStack console = helper.getLevel().getServer().createCommandSourceStack();
        rejects(helper, console, "fly");
        rejects(helper, console, "fly 2");
        helper.succeed();
    }

    @GameTest
    public void accessSettingPersists(GameTestHelper helper) throws IOException {
        Path directory = Files.createTempDirectory("flymod-config-test-");
        Path path = directory.resolve("flymod.properties");
        try {
            FlyConfig config = new FlyConfig(path);
            helper.assertFalse(config.operatorsOnly(), "Flight must be public by default");
            config.setOperatorsOnly(true);
            helper.assertTrue(new FlyConfig(path).operatorsOnly(), "Operators-only access must survive reload");
            config.setOperatorsOnly(false);
            helper.assertFalse(new FlyConfig(path).operatorsOnly(), "Public access must survive reload");
        } finally {
            Files.deleteIfExists(path);
            Files.deleteIfExists(directory);
        }
        helper.succeed();
    }

    private static ServerPlayer player(GameTestHelper helper, GameType mode) {
        return player(helper, mode, new GameProfile(UUID.randomUUID(), "FlyTest"));
    }

    private static ServerPlayer player(GameTestHelper helper, GameType mode, GameProfile profile) {
        MinecraftServer server = helper.getLevel().getServer();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(server, helper.getLevel(), profile, cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(mode);
        cleanup(helper, player);
        return player;
    }

    private static void cleanup(GameTestHelper helper, ServerPlayer player) {
        helper.runBeforeTestEnd(() -> {
            if (helper.getLevel().getServer().getPlayerList().getPlayers().contains(player)) {
                helper.getLevel().getServer().getPlayerList().remove(player);
            }
        });
    }

    private static void execute(ServerPlayer player, String command) throws CommandSyntaxException {
        execute(player.createCommandSourceStack().withPermission(LevelBasedPermissionSet.ALL), command);
    }

    private static void execute(CommandSourceStack source, String command) throws CommandSyntaxException {
        source.getServer().getCommands().getDispatcher().execute(command, source);
    }

    private static void rejects(GameTestHelper helper, CommandSourceStack source, String command) {
        try {
            execute(source, command);
        } catch (CommandSyntaxException expected) {
            return;
        }
        helper.fail("Command should have failed: " + command);
    }
}
