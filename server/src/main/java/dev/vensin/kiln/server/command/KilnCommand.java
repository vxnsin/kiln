package dev.vensin.kiln.server.command;

import dev.vensin.kiln.server.plugin.PluginManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandManager;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.CommandExecutor;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.arguments.ArgumentWord;
import net.minestom.server.entity.Player;

/** Everything the server itself offers lives under /kiln, so plugins keep the rest of the namespace. */
public final class KilnCommand {

    public static final String NAME = "kiln";
    private static final String REPOSITORY = "https://github.com/vxnsin/kiln";
    private static final Map<String, String> HELP = new LinkedHashMap<>();

    static {
        HELP.put("plugins", "loaded plugins and their versions");
        HELP.put("players", "who is online");
        HELP.put("mem", "memory in use");
        HELP.put("version", "the running version");
        HELP.put("update", "look for a newer release and fetch it");
        HELP.put("op [name]", "give a player admin rights");
        HELP.put("deop [name]", "take them away again");
        HELP.put("stop", "save and shut down");
    }

    private final Operators operators;
    private final PluginManager plugins;
    private final String version;

    private KilnCommand(Operators operators, PluginManager plugins, String version) {
        this.operators = operators;
        this.plugins = plugins;
        this.version = version;
    }

    public static void register(CommandManager commands, Operators operators, PluginManager plugins, String version) {
        KilnCommand kiln = new KilnCommand(operators, plugins, version);
        Command root = new Command(NAME);
        root.setDefaultExecutor((sender, context) -> {
            if (operators.allows(sender)) {
                kiln.help(sender);
            } else {
                kiln.version(sender);
            }
        });
        root.addSubcommands(
                kiln.admin("help", (sender, context) -> kiln.help(sender)),
                kiln.admin("plugins", (sender, context) -> kiln.plugins(sender)),
                kiln.admin("players", (sender, context) -> kiln.players(sender)),
                kiln.admin("mem", (sender, context) -> kiln.memory(sender)),
                kiln.admin("version", (sender, context) -> kiln.version(sender)),
                kiln.admin("update", (sender, context) -> Thread.ofVirtual().start(() -> kiln.update(sender))),
                kiln.named("op", true),
                kiln.named("deop", false),
                kiln.admin("stop", (sender, context) -> kiln.stop(sender)));

        // Typing just "stop" into a console is a habit worth keeping.
        Command stop = kiln.admin("stop", (sender, context) -> kiln.stop(sender));
        commands.register(root, stop);
    }

    public static List<String> subcommands() {
        return HELP.keySet().stream().map(usage -> usage.split(" ")[0]).toList();
    }

    private void help(CommandSender sender) {
        Chat.say(sender, "<white>Commands</white>");
        HELP.forEach((usage, text) -> Chat.item(sender, "<gold>/kiln " + usage + "</gold> <gray>" + text + "</gray>"));
    }

    private void plugins(CommandSender sender) {
        List<PluginManager.Loaded> loaded = plugins.loaded();
        if (loaded.isEmpty()) {
            Chat.say(sender, "<gray>No plugins loaded. Drop jars into the plugins folder and restart.</gray>");
            return;
        }
        Chat.say(sender, "<white>" + loaded.size() + " plugins</white>");
        loaded.forEach(plugin -> Chat.item(sender, "<white>" + plugin.info().name() + "</white> <gray>"
                + plugin.info().version() + "</gray>"));
    }

    private void players(CommandSender sender) {
        List<String> names = MinecraftServer.getConnectionManager().getOnlinePlayers().stream()
                .map(Player::getUsername).sorted().toList();
        Chat.say(sender, names.isEmpty() ? "<gray>Nobody is online.</gray>"
                : "<white>" + names.size() + " online</white> <gray>" + String.join(", ", names) + "</gray>");
    }

    private void memory(CommandSender sender) {
        Runtime runtime = Runtime.getRuntime();
        long megabyte = 1024 * 1024;
        Chat.say(sender, "<white>" + (runtime.totalMemory() - runtime.freeMemory()) / megabyte
                + " MB</white> <gray>in use, limit " + runtime.maxMemory() / megabyte + " MB</gray>");
    }

    private void version(CommandSender sender) {
        Chat.say(sender, "<white>Kiln " + version + "</white> <gray>" + REPOSITORY + "</gray>");
    }

    private void update(CommandSender sender) {
        try {
            Optional<UpdateCheck.Release> latest = UpdateCheck.latest();
            if (latest.isEmpty()) {
                Chat.say(sender, "<gray>No release has been published yet. You are on " + version + ".</gray>");
            } else if (!UpdateCheck.isNewer(latest.get().version(), version)) {
                Chat.say(sender, "<green>Kiln " + version + " is the newest version.</green>");
            } else if (latest.get().jar() == null) {
                Chat.say(sender, "<white>Kiln " + latest.get().version() + " is out:</white> <gray>"
                        + latest.get().page() + "</gray>");
            } else {
                Path target = Path.of("kiln-" + latest.get().version() + ".jar");
                if (!Files.exists(target)) {
                    Chat.say(sender, "<gray>Fetching Kiln " + latest.get().version() + "...</gray>");
                    UpdateCheck.download(latest.get(), target);
                }
                Chat.say(sender, "<green>" + target + " is ready.</green> <gray>Stop the server and start that file.</gray>");
            }
        } catch (IOException e) {
            Chat.error(sender, "Update check failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void stop(CommandSender sender) {
        Chat.say(sender, "<gray>Shutting down</gray>");
        MinecraftServer.stopCleanly();
        System.exit(0);
    }

    private Command admin(String name, CommandExecutor executor) {
        Command command = new Command(name);
        command.setCondition((sender, input) -> operators.allows(sender));
        command.setDefaultExecutor(executor);
        return command;
    }

    private Command named(String name, boolean grant) {
        ArgumentWord player = ArgumentType.Word("name");
        Command command = admin(name, (sender, context) -> Chat.say(sender, "<gray>Usage: /kiln " + name + " [name]</gray>"));
        command.addSyntax((sender, context) -> change(sender, context.get(player), grant), player);
        return command;
    }

    private void change(CommandSender sender, String name, boolean grant) {
        try {
            boolean changed = grant ? operators.add(name) : operators.remove(name);
            if (!changed) {
                Chat.say(sender, "<gray>" + name + (grant ? " is already an operator." : " is not an operator.") + "</gray>");
                return;
            }
            Chat.say(sender, "<green>" + name + (grant ? " is now an operator." : " is no longer an operator.") + "</green>");
            Player online = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(name);
            if (online != null) {
                online.refreshCommands();
            }
        } catch (IOException e) {
            Chat.error(sender, "Could not save ops.txt: " + e.getMessage());
        }
    }
}
