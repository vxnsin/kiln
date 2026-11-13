package dev.vensin.kiln.server.command;

import dev.vensin.kiln.server.console.InteractiveConsole;
import dev.vensin.kiln.server.plugin.PluginManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandManager;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.CommandExecutor;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.arguments.ArgumentWord;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;
import net.minestom.server.entity.Player;

/** Everything the server itself offers lives under /kiln, so plugins keep the rest of the namespace. */
public final class KilnCommand {

    public static final String NAME = "kiln";
    private static final String REPOSITORY = "https://github.com/vxnsin/kiln";
    private static final Map<String, String> HELP = new LinkedHashMap<>();

    static {
        HELP.put("plugins", "loaded plugins and their versions");
        HELP.put("reload [plugin]", "swap a plugin for the jar now in the folder");
        HELP.put("load", "load jars that were added to the folder");
        HELP.put("unload [plugin]", "take a plugin out");
        HELP.put("players", "who is online");
        HELP.put("performance", "ticks, memory, CPU and what the world holds (short: perf)");
        HELP.put("version", "the running version");
        HELP.put("update", "look for a newer release and fetch it");
        HELP.put("maintenance", "let only operators and allowed players in");
        HELP.put("op [name]", "give a player admin rights");
        HELP.put("deop [name]", "take them away again");
        HELP.put("stop", "save and shut down");
    }

    private final Operators operators;
    private final PluginManager plugins;
    private final Maintenance maintenance;
    private final String version;

    private KilnCommand(Operators operators, PluginManager plugins, Maintenance maintenance, String version) {
        this.operators = operators;
        this.plugins = plugins;
        this.maintenance = maintenance;
        this.version = version;
    }

    public static void register(CommandManager commands, Operators operators, PluginManager plugins,
                                Performance performance, Maintenance maintenance, String version) {
        KilnCommand kiln = new KilnCommand(operators, plugins, maintenance, version);
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
                kiln.withPlugin("reload", kiln::reload),
                kiln.admin("load", (sender, context) -> MinecraftServer.getSchedulerManager()
                        .scheduleNextTick(() -> kiln.load(sender))),
                kiln.withPlugin("unload", (sender, name) -> kiln.unload(sender, name, true)),
                kiln.admin("performance", (sender, context) -> performance.report(sender), "perf"),
                kiln.admin("version", (sender, context) -> kiln.version(sender)),
                kiln.admin("update", (sender, context) -> Thread.ofVirtual().start(() -> kiln.update(sender))),
                kiln.withName("op", "op", (sender, name) -> kiln.change(sender, name, true)),
                kiln.withName("deop", "deop", (sender, name) -> kiln.change(sender, name, false)),
                kiln.maintenanceCommand(),
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
            Chat.say(sender, "<gray>No plugins loaded. Drop jars into the plugins folder and type kiln load.</gray>");
            return;
        }
        Chat.say(sender, "<white>" + loaded.size() + (loaded.size() == 1 ? " plugin" : " plugins") + "</white>");
        loaded.forEach(plugin -> Chat.item(sender, "<white>" + plugin.info().name() + "</white> <gray>"
                + plugin.info().version() + "</gray>"));
    }

    private void players(CommandSender sender) {
        List<String> names = MinecraftServer.getConnectionManager().getOnlinePlayers().stream()
                .map(Player::getUsername).sorted().toList();
        Chat.say(sender, names.isEmpty() ? "<gray>Nobody is online.</gray>"
                : "<white>" + names.size() + " online</white> <gray>" + String.join(", ", names) + "</gray>");
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
        MinecraftServer.getConnectionManager().getOnlinePlayers().forEach(player -> player.kick(
                Chat.screen("The server is shutting down", "It will be back shortly.")));
        MinecraftServer.stopCleanly();
        System.exit(0);
    }

    private Command admin(String name, CommandExecutor executor, String... aliases) {
        Command command = new Command(name, aliases);
        command.setCondition((sender, input) -> operators.allows(sender));
        command.setDefaultExecutor(executor);
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

    private Command maintenanceCommand() {
        Command command = admin("maintenance", (sender, context) -> maintenanceStatus(sender));
        command.addSubcommands(
                admin("on", (sender, context) -> {
                    maintenance.set(true);
                    Chat.say(sender, "<yellow>Maintenance is on.</yellow> <gray>Only operators and allowed players get in.</gray>");
                }),
                admin("off", (sender, context) -> {
                    maintenance.set(false);
                    Chat.say(sender, "<green>Maintenance is off.</green> <gray>Everyone can join again.</gray>");
                }),
                withName("allow", "maintenance allow", (sender, name) -> Chat.say(sender, maintenance.allow(name)
                        ? "<green>" + name + " may join during maintenance.</green>"
                        : "<gray>" + name + " is already allowed.</gray>")),
                withName("deny", "maintenance deny", (sender, name) -> Chat.say(sender, maintenance.deny(name)
                        ? "<green>" + name + " is no longer allowed during maintenance.</green>"
                        : "<gray>" + name + " was not on the list.</gray>")));
        return command;
    }

    private void maintenanceStatus(CommandSender sender) {
        Chat.say(sender, maintenance.enabled() ? "<yellow>Maintenance is on</yellow>" : "<white>Maintenance is off</white>");
        Chat.item(sender, "<gold>/kiln maintenance on</gold> <gray>or</gray> <gold>off</gold>");
        Chat.item(sender, "<gold>/kiln maintenance allow [name]</gold> <gray>or</gray> <gold>deny [name]</gold>");
        Chat.item(sender, "<gray>Allowed besides operators:</gray> <white>"
                + (maintenance.allowed().isEmpty() ? "nobody" : String.join(", ", maintenance.allowed())) + "</white>");
    }

    private Command withName(String name, String usage, BiConsumer<CommandSender, String> action) {
        ArgumentWord player = ArgumentType.Word("name");
        Command command = admin(name, (sender, context) -> Chat.say(sender, "<gray>Usage: /kiln " + usage + " [name]</gray>"));
        command.addSyntax((sender, context) -> action.accept(sender, context.get(player)), player);
        return command;
    }

    private Command withPlugin(String name, BiConsumer<CommandSender, String> action) {
        ArgumentWord plugin = ArgumentType.Word("plugin");
        plugin.setSuggestionCallback((sender, context, suggestion) -> pluginNames()
                .forEach(loaded -> suggestion.addEntry(new SuggestionEntry(loaded))));
        InteractiveConsole.suggest(name, this::pluginNames);
        Command command = admin(name, (sender, context) -> Chat.say(sender, "<gray>Usage: /kiln " + name + " [plugin]</gray>"));
        // Swapping happens between two ticks, so no event is halfway through a plugin that is going away.
        command.addSyntax((sender, context) -> MinecraftServer.getSchedulerManager()
                .scheduleNextTick(() -> action.accept(sender, context.get(plugin))), plugin);
        return command;
    }

    private List<String> pluginNames() {
        return plugins.loaded().stream().map(loaded -> loaded.info().name()).toList();
    }

    private void reload(CommandSender sender, String name) {
        String before = plugins.loaded().stream().filter(loaded -> loaded.info().name().equals(name))
                .map(loaded -> loaded.info().version()).findFirst().orElse(null);
        if (before == null) {
            Chat.error(sender, name + " is not loaded.");
            return;
        }
        if (!unload(sender, name, false)) {
            return;
        }
        List<PluginManager.Loaded> added = loadNew(sender);
        added.stream().filter(loaded -> loaded.info().name().equals(name)).findFirst().ifPresent(loaded ->
                Chat.say(sender, "<green>" + name + " swapped.</green> <gray>" + before + " → " + loaded.info().version() + "</gray>"));
    }

    private boolean unload(CommandSender sender, String name, boolean announce) {
        try {
            if (!plugins.unload(name)) {
                Chat.error(sender, name + " is not loaded.");
                return false;
            }
        } catch (IllegalStateException blocked) {
            Chat.error(sender, name + " cannot go: " + blocked.getMessage() + ".");
            return false;
        }
        if (announce) {
            Chat.say(sender, "<green>" + name + " unloaded.</green>");
            refreshCommands();
        }
        return true;
    }

    private List<PluginManager.Loaded> loadNew(CommandSender sender) {
        List<PluginManager.Loaded> added;
        try {
            added = plugins.loadNew();
        } catch (IOException e) {
            Chat.error(sender, "Could not read the plugins folder: " + e.getMessage());
            return List.of();
        }
        plugins.failures().forEach(failure -> Chat.error(sender, failure.name() + " " + failure.version() + ": "
                + failure.reason()));
        refreshCommands();
        return added;
    }

    private void load(CommandSender sender) {
        List<PluginManager.Loaded> added = loadNew(sender);
        if (added.isEmpty() && plugins.failures().isEmpty()) {
            Chat.say(sender, "<gray>Nothing new in the plugins folder.</gray>");
        }
        added.forEach(loaded -> Chat.say(sender, "<green>" + loaded.info().name() + " loaded.</green> <gray>"
                + loaded.info().version() + "</gray>"));
    }

    private static void refreshCommands() {
        MinecraftServer.getConnectionManager().getOnlinePlayers().forEach(Player::refreshCommands);
    }
}
