package dev.vensin.kiln.server.command;

import dev.vensin.kiln.server.console.InteractiveConsole;
import dev.vensin.kiln.server.network.NetworkService;
import dev.vensin.kiln.server.pack.ResourcePackHost;
import dev.vensin.kiln.server.world.WorldFiles;
import dev.vensin.kiln.server.plugin.PluginManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandManager;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.CommandExecutor;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.arguments.ArgumentStringArray;
import net.minestom.server.command.builder.arguments.ArgumentWord;
import net.minestom.server.command.builder.arguments.number.ArgumentInteger;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;
import net.minestom.server.dialog.Dialog;
import net.minestom.server.dialog.DialogAction;
import net.minestom.server.dialog.DialogActionButton;
import net.minestom.server.dialog.DialogAfterAction;
import net.minestom.server.dialog.DialogBody;
import net.minestom.server.dialog.DialogMetadata;
import net.kyori.adventure.text.Component;
import net.minestom.server.entity.Player;

/** Everything the server itself offers lives under /kiln, so plugins keep the rest of the namespace. */
public final class KilnCommand {

    public static final String NAME = "kiln";
    private static final String REPOSITORY = "https://github.com/vxnsin/kiln";
    private static final Map<String, String> HELP = new LinkedHashMap<>();

    static {
        HELP.put("menu", "the most used commands as buttons, in game");
        HELP.put("plugins", "loaded plugins and their versions");
        HELP.put("reload [plugin]", "swap a plugin for the jar now in the folder");
        HELP.put("load", "load jars that were added to the folder");
        HELP.put("unload [plugin]", "take a plugin out");
        HELP.put("install [owner/repo]", "fetch a plugin from a GitHub release or a https link");
        HELP.put("profile [seconds]", "which methods and plugins the server threads spend their time in");
        HELP.put("players", "who is online");
        HELP.put("servers", "every server in the network and its players");
        HELP.put("find [player]", "which server a player is on");
        HELP.put("send [player] [server]", "move a player to another server, no proxy needed");
        HELP.put("gm [mode] [player]", "survival, creative, adventure or spectator");
        HELP.put("tp [player] [target]", "go to a player, or send one to another");
        HELP.put("world [name]", "visit a map from worlds/, or home");
        HELP.put("performance", "ticks, memory, CPU and what the world holds (short: perf)");
        HELP.put("version", "the running version");
        HELP.put("update", "look for a newer release and fetch it");
        HELP.put("maintenance", "let only operators and allowed players in");
        HELP.put("group", "permission groups from permissions.yml");
        HELP.put("pack", "read the resource pack again and send it to everyone");
        HELP.put("config reload", "read kiln.yml again without a restart");
        HELP.put("op [name]", "give a player admin rights");
        HELP.put("deop [name]", "take them away again");
        HELP.put("kick [player] [reason]", "send a player away with a reason");
        HELP.put("ban [player] [time] [reason]", "keep a player out, for good or for 30m, 12h, 7d, 2w");
        HELP.put("unban [player]", "let them back in; bans lists who is out");
        HELP.put("say [message]", "announce something to everyone");
        HELP.put("stop [seconds] [reason]", "shut down, now or after a visible countdown");
    }

    private final Operators operators;
    private final Permissions permissions;
    private final PluginManager plugins;
    private final Maintenance maintenance;
    private final String version;
    private final ResourcePackHost pack;
    private final NetworkService network;
    private final Bans bans;
    private ConfigReload configReload = () -> { };
    private final Countdown countdown = new Countdown();

    private KilnCommand(Operators operators, Permissions permissions, PluginManager plugins, Maintenance maintenance,
                        String version, ResourcePackHost pack, NetworkService network, Bans bans) {
        this.operators = operators;
        this.permissions = permissions;
        this.plugins = plugins;
        this.maintenance = maintenance;
        this.version = version;
        this.pack = pack;
        this.network = network;
        this.bans = bans;
    }

    public static KilnCommand register(CommandManager commands, Operators operators, Permissions permissions,
                                       PluginManager plugins,
                                Performance performance, Maintenance maintenance, String version,
                                ResourcePackHost pack, NetworkService network, WorldFiles worlds, Bans bans) {
        KilnCommand kiln = new KilnCommand(operators, permissions, plugins, maintenance, version, pack, network, bans);
        Command root = new Command(NAME);
        root.setDefaultExecutor((sender, context) -> {
            if (permissions.has(sender, "kiln.help")) {
                kiln.help(sender);
            } else {
                kiln.version(sender);
            }
        });
        root.addSubcommands(
                kiln.admin("help", "kiln.help", (sender, context) -> kiln.help(sender)),
                kiln.admin("menu", "kiln.help", (sender, context) -> {
                    if (sender instanceof Player player) {
                        kiln.openMenu(player);
                    } else {
                        kiln.help(sender);
                    }
                }),
                kiln.admin("plugins", "kiln.plugins", (sender, context) -> kiln.plugins(sender)),
                kiln.admin("players", "kiln.players", (sender, context) -> kiln.players(sender)),
                kiln.admin("servers", "kiln.servers", (sender, context) -> kiln.servers(sender)),
                kiln.findCommand(),
                kiln.sendCommand(),
                kiln.withPlugin("reload", kiln::reload),
                kiln.admin("load", "kiln.load", (sender, context) -> MinecraftServer.getSchedulerManager()
                        .scheduleNextTick(() -> kiln.load(sender))),
                kiln.withPlugin("unload", (sender, name) -> kiln.unload(sender, name, true)),
                kiln.installCommand(),
                kiln.profileCommand(),
                kiln.admin("performance", "kiln.performance", (sender, context) -> performance.report(sender), "perf"),
                kiln.admin("version", "kiln.version", (sender, context) -> kiln.version(sender)),
                kiln.admin("update", "kiln.update", (sender, context) -> Thread.ofVirtual().start(() -> kiln.update(sender))),
                kiln.withName("op", "op", (sender, name) -> kiln.change(sender, name, true)),
                kiln.withName("deop", "deop", (sender, name) -> kiln.change(sender, name, false)),
                kiln.maintenanceCommand(),
                kiln.groupCommand(),
                kiln.configCommand(),
                kiln.admin("pack", "kiln.pack", (sender, context) -> kiln.resendPack(sender)),
                kiln.kickCommand(),
                kiln.banCommand(),
                kiln.unbanCommand(),
                kiln.admin("bans", "kiln.ban", (sender, context) -> kiln.listBans(sender)),
                kiln.sayCommand(),
                kiln.stopCommand());

        new TravelCommands(permissions, worlds).commands().forEach(root::addSubcommand);

        // Typing just "stop" into a console is a habit worth keeping.
        Command stop = kiln.admin("stop", "kiln.stop", (sender, context) -> kiln.stop(sender, ""));
        kiln.countdown.register(MinecraftServer.getGlobalEventHandler());
        commands.register(root, stop);
        return kiln;
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

    private void stop(CommandSender sender, String reason) {
        Chat.say(sender, "<gray>Shutting down</gray>");
        String advice = MiniMessage.miniMessage().escapeTags(reason);
        MinecraftServer.getConnectionManager().getOnlinePlayers().forEach(player -> player.kick(
                ServerTexts.screen("stopping", advice)));
        MinecraftServer.stopCleanly();
        System.exit(0);
    }

    private Command stopCommand() {
        ArgumentInteger seconds = ArgumentType.Integer("seconds");
        seconds.between(1, 3600);
        ArgumentStringArray reason = ArgumentType.StringArray("reason");
        Command command = admin("stop", "kiln.stop", (sender, context) -> stop(sender, ""));
        CommandExecutor countdownExecutor = (sender, context) -> {
            int time = context.get(seconds);
            String why = context.has(reason) ? String.join(" ", context.get(reason)) : "";
            countdown.start(time, why, done -> stop(sender, done));
            broadcast("<yellow>The server stops in " + Countdown.time(time) + ".</yellow>"
                    + (why.isBlank() ? "" : " <gray>" + MiniMessage.miniMessage().escapeTags(why) + "</gray>"));
        };
        command.addSyntax(countdownExecutor, seconds);
        command.addSyntax(countdownExecutor, seconds, reason);
        command.addSubcommand(admin("cancel", "kiln.stop", (sender, context) -> {
            if (countdown.cancel()) {
                broadcast("<green>The stop was called off.</green>");
            } else {
                Chat.say(sender, "<gray>No countdown is running.</gray>");
            }
        }));
        return command;
    }

    private Command kickCommand() {
        ArgumentWord player = ArgumentType.Word("player");
        player.setSuggestionCallback((sender, context, suggestion) -> MinecraftServer.getConnectionManager()
                .getOnlinePlayers().forEach(online -> suggestion.addEntry(new SuggestionEntry(online.getUsername()))));
        ArgumentStringArray reason = ArgumentType.StringArray("reason");
        Command command = admin("kick", "kiln.kick",
                (sender, context) -> Chat.say(sender, "<gray>Usage: /kiln kick [player] [reason]</gray>"));
        CommandExecutor kick = (sender, context) -> {
            Player target = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(context.get(player));
            if (target == null) {
                Chat.error(sender, context.get(player) + " is not online.");
                return;
            }
            String why = context.has(reason) ? String.join(" ", context.get(reason)) : "";
            target.kick(ServerTexts.screen("kicked", MiniMessage.miniMessage().escapeTags(why)));
            Chat.say(sender, "<green>" + target.getUsername() + " was kicked.</green>");
        };
        command.addSyntax(kick, player);
        command.addSyntax(kick, player, reason);
        return command;
    }

    private Command sayCommand() {
        ArgumentStringArray message = ArgumentType.StringArray("message");
        Command command = admin("say", "kiln.say",
                (sender, context) -> Chat.say(sender, "<gray>Usage: /kiln say [message]</gray>"));
        command.addSyntax((sender, context) -> broadcast("<white>"
                + MiniMessage.miniMessage().escapeTags(String.join(" ", context.get(message))) + "</white>"), message);
        return command;
    }

    /** To every player and the console, with the server's prefix. */
    private static void broadcast(String miniMessage) {
        MinecraftServer.getConnectionManager().getOnlinePlayers().forEach(player -> Chat.say(player, miniMessage));
        Chat.say(MinecraftServer.getCommandManager().getConsoleSender(), miniMessage);
    }

    private Command admin(String name, String permission, CommandExecutor executor, String... aliases) {
        Command command = new Command(name, aliases);
        command.setCondition((sender, input) -> permissions.has(sender, permission));
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
        Command command = admin("maintenance", "kiln.maintenance", (sender, context) -> maintenanceStatus(sender),
                "whitelist");
        command.addSubcommands(
                admin("on", "kiln.maintenance", (sender, context) -> {
                    maintenance.set(true);
                    Chat.say(sender, "<yellow>Maintenance is on.</yellow> <gray>Only operators and allowed players get in.</gray>");
                }),
                admin("off", "kiln.maintenance", (sender, context) -> {
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
        Command command = admin(name, "kiln." + usage.split(" ")[0],
                (sender, context) -> Chat.say(sender, "<gray>Usage: /kiln " + usage + " [name]</gray>"));
        command.addSyntax((sender, context) -> action.accept(sender, context.get(player)), player);
        return command;
    }

    private Command withPlugin(String name, BiConsumer<CommandSender, String> action) {
        ArgumentWord plugin = ArgumentType.Word("plugin");
        plugin.setSuggestionCallback((sender, context, suggestion) -> pluginNames()
                .forEach(loaded -> suggestion.addEntry(new SuggestionEntry(loaded))));
        InteractiveConsole.suggest(name, this::pluginNames);
        Command command = admin(name, "kiln." + name,
                (sender, context) -> Chat.say(sender, "<gray>Usage: /kiln " + name + " [plugin]</gray>"));
        // Swapping happens between two ticks, so no event is halfway through a plugin that is going away.
        command.addSyntax((sender, context) -> MinecraftServer.getSchedulerManager()
                .scheduleNextTick(() -> action.accept(sender, context.get(plugin))), plugin);
        return command;
    }

    private List<String> pluginNames() {
        return plugins.loaded().stream().map(loaded -> loaded.info().name()).toList();
    }

    /** Plugins that depend on this one come out first and go back in with it, so they see the new code. */
    public void reload(CommandSender sender, String name) {
        String before = plugins.loaded().stream().filter(loaded -> loaded.info().name().equals(name))
                .map(loaded -> loaded.info().version()).findFirst().orElse(null);
        if (before == null) {
            Chat.error(sender, name + " is not loaded.");
            return;
        }
        List<String> chain = new ArrayList<>();
        collectDependents(name, chain);
        Optional<PluginManager.Backup> backup = plugins.backup(name);
        for (int i = chain.size() - 1; i >= 0; i--) {
            if (!unload(sender, chain.get(i), false)) {
                backup.ifPresent(plugins::discard);
                return;
            }
        }
        Optional<PluginManager.Loaded> swapped = loadNew(sender).stream()
                .filter(loaded -> loaded.info().name().equals(name)).findFirst();
        if (swapped.isPresent()) {
            backup.ifPresent(plugins::discard);
            Chat.say(sender, "<green>" + name + " swapped.</green> <gray>" + before + " → " + swapped.get().info().version()
                    + (chain.size() > 1 ? ", with " + String.join(", ", chain.subList(1, chain.size())) : "") + "</gray>");
        } else if (backup.isPresent() && plugins.restore(backup.get())) {
            Chat.say(sender, "<yellow>The new build of " + name + " did not start, so " + before
                    + " is running again.</yellow> <gray>Fix it and replace the jar once more.</gray>");
            loadNew(sender);
        }
    }

    /** The plugin, then everything that needs it, in an order where each comes after what it needs. */
    private void collectDependents(String name, List<String> into) {
        if (into.contains(name)) {
            return;
        }
        into.add(name);
        plugins.dependents(name).forEach(dependent -> collectDependents(dependent, into));
    }

    public boolean unload(CommandSender sender, String name, boolean announce) {
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

    public void load(CommandSender sender) {
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

    private Command groupCommand() {
        Command command = admin("group", "kiln.group", (sender, context) -> groupStatus(sender));
        ArgumentWord player = ArgumentType.Word("player");
        ArgumentWord group = ArgumentType.Word("group");
        group.setSuggestionCallback((sender, context, suggestion) -> permissions.groupNames()
                .forEach(name -> suggestion.addEntry(new SuggestionEntry(name))));
        Command add = admin("add", "kiln.group", (sender, context) -> Chat.say(sender, "<gray>Usage: /kiln group add [player] [group]</gray>"));
        add.addSyntax((sender, context) -> changeGroup(sender, context.get(player), context.get(group), true), player, group);
        Command remove = admin("remove", "kiln.group", (sender, context) -> Chat.say(sender, "<gray>Usage: /kiln group remove [player] [group]</gray>"));
        remove.addSyntax((sender, context) -> changeGroup(sender, context.get(player), context.get(group), false), player, group);
        Command reload = admin("reload", "kiln.group", (sender, context) -> {
            try {
                permissions.reload();
                Chat.say(sender, "<green>permissions.yml reloaded.</green>");
                refreshCommands();
            } catch (IOException | RuntimeException e) {
                Chat.error(sender, "permissions.yml could not be read: " + e.getMessage());
            }
        });
        command.addSubcommands(add, remove, reload);
        return command;
    }

    private void groupStatus(CommandSender sender) {
        Chat.say(sender, "<white>Groups</white> <gray>" + String.join(", ", permissions.groupNames()) + "</gray>");
        Chat.item(sender, "<gold>/kiln group add [player] [group]</gold> <gray>or</gray> <gold>remove</gold>");
        Chat.item(sender, "<gold>/kiln group reload</gold> <gray>after editing permissions.yml</gray>");
    }

    private void changeGroup(CommandSender sender, String player, String group, boolean add) {
        try {
            boolean changed = add ? permissions.give(player, group) : permissions.take(player, group);
            Chat.say(sender, changed
                    ? "<green>" + player + (add ? " is now in " : " is no longer in ") + group + ".</green>"
                    : "<gray>" + player + (add ? " is already in " : " is not in ") + group + ".</gray>");
            refreshCommands();
        } catch (IllegalArgumentException e) {
            Chat.error(sender, e.getMessage());
        } catch (IOException e) {
            Chat.error(sender, "Could not save permissions.yml: " + e.getMessage());
        }
    }

    private void resendPack(CommandSender sender) {
        if (pack == null) {
            Chat.say(sender, "<gray>No resource pack. Drop pack.zip next to the server and restart.</gray>");
            return;
        }
        try {
            String hash = pack.reload();
            pack.sendToAll();
            Chat.say(sender, "<green>Resource pack sent to everyone.</green> <gray>" + pack.size() + ", "
                    + hash.substring(0, 10) + "</gray>");
        } catch (IOException e) {
            Chat.error(sender, "Could not read the resource pack: " + e.getMessage());
        }
    }

    private void servers(CommandSender sender) {
        var servers = network.servers();
        int players = servers.stream().mapToInt(server -> server.players().size()).sum();
        Chat.say(sender, "<white>" + servers.size() + (servers.size() == 1 ? " server" : " servers") + ", " + players
                + (players == 1 ? " player" : " players") + "</white>");
        for (var server : servers) {
            String self = server.id().equals(network.serverId()) ? " <gold>(this one)</gold>" : "";
            String address = server.address() == null || server.address().isBlank() ? "" : " <dark_gray>" + server.address() + "</dark_gray>";
            Chat.item(sender, "<white>" + MiniMessage.miniMessage().escapeTags(server.id()) + "</white>" + self + address
                    + " <gray>" + server.players().size() + "/" + server.maxPlayers()
                    + (server.players().isEmpty() ? "" : " · " + String.join(", ", server.players())) + "</gray>");
        }
    }

    private Command findCommand() {
        ArgumentWord player = ArgumentType.Word("player");
        Command command = admin("find", "kiln.find",
                (sender, context) -> Chat.say(sender, "<gray>Usage: /kiln find [player]</gray>"));
        command.addSyntax((sender, context) -> {
            String name = context.get(player);
            Chat.say(sender, network.serverOf(name)
                    .map(server -> "<white>" + name + "</white> <gray>is on</gray> <white>"
                            + MiniMessage.miniMessage().escapeTags(server) + "</white>")
                    .orElse("<gray>" + name + " is not online anywhere.</gray>"));
        }, player);
        return command;
    }

    private Command banCommand() {
        ArgumentWord player = ArgumentType.Word("player");
        player.setSuggestionCallback((sender, context, suggestion) -> MinecraftServer.getConnectionManager()
                .getOnlinePlayers().forEach(online -> suggestion.addEntry(new SuggestionEntry(online.getUsername()))));
        ArgumentStringArray rest = ArgumentType.StringArray("duration and reason");
        Command command = admin("ban", "kiln.ban",
                (sender, context) -> Chat.say(sender, "<gray>Usage: /kiln ban [player] [30m|12h|7d|2w] [reason]</gray>"));
        CommandExecutor ban = (sender, context) -> {
            String name = context.get(player);
            List<String> words = context.has(rest) ? new ArrayList<>(List.of(context.get(rest))) : new ArrayList<>();
            Duration length = words.isEmpty() ? null : Bans.parse(words.getFirst());
            if (length != null) {
                words.removeFirst();
            }
            String by = sender instanceof Player self ? self.getUsername() : "console";
            Bans.Ban done = bans.ban(name, length, String.join(" ", words), by);
            Player online = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(name);
            if (online != null) {
                online.kick(Bans.screen(done));
            }
            Chat.say(sender, "<green>" + name + " is banned " + Bans.describe(done) + ".</green>");
        };
        command.addSyntax(ban, player);
        command.addSyntax(ban, player, rest);
        return command;
    }

    private Command unbanCommand() {
        ArgumentWord player = ArgumentType.Word("player");
        player.setSuggestionCallback((sender, context, suggestion) -> bans.all()
                .forEach(ban -> suggestion.addEntry(new SuggestionEntry(ban.name()))));
        Command command = admin("unban", "kiln.ban",
                (sender, context) -> Chat.say(sender, "<gray>Usage: /kiln unban [player]</gray>"));
        command.addSyntax((sender, context) -> {
            String name = context.get(player);
            Chat.say(sender, bans.unban(name) ? "<green>" + name + " may join again.</green>"
                    : "<gray>" + name + " was not banned.</gray>");
        }, player);
        return command;
    }

    private void listBans(CommandSender sender) {
        List<Bans.Ban> all = bans.all();
        if (all.isEmpty()) {
            Chat.say(sender, "<gray>Nobody is banned.</gray>");
            return;
        }
        Chat.say(sender, "<white>" + all.size() + (all.size() == 1 ? " ban" : " bans") + "</white>");
        all.forEach(ban -> Chat.item(sender, "<white>" + ban.name() + "</white> <gray>" + Bans.describe(ban) + ", by "
                + ban.by() + (ban.reason().isBlank() ? "" : " · " + MiniMessage.miniMessage().escapeTags(ban.reason()))
                + "</gray>"));
    }

    private Command sendCommand() {
        ArgumentWord player = ArgumentType.Word("player");
        player.setSuggestionCallback((sender, context, suggestion) -> MinecraftServer.getConnectionManager()
                .getOnlinePlayers().forEach(online -> suggestion.addEntry(new SuggestionEntry(online.getUsername()))));
        ArgumentWord server = ArgumentType.Word("server");
        server.setSuggestionCallback((sender, context, suggestion) -> network.servers().stream()
                .filter(info -> !info.id().equals(network.serverId()) && !info.address().isBlank())
                .forEach(info -> suggestion.addEntry(new SuggestionEntry(info.id()))));
        Command command = admin("send", "kiln.send",
                (sender, context) -> Chat.say(sender, "<gray>Usage: /kiln send [player] [server]</gray>"));
        command.addSyntax((sender, context) -> {
            Player target = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(context.get(player));
            if (target == null) {
                Chat.error(sender, context.get(player) + " is not on this server.");
            } else if (network.send(target, context.get(server))) {
                Chat.say(sender, "<green>" + target.getUsername() + " is on the way to " + context.get(server) + ".</green>");
            } else {
                Chat.error(sender, context.get(server) + " is not a server in the network with a public-address.");
            }
        }, player, server);
        return command;
    }

    /** What a reload of kiln.yml does; Settings.load can fail, which the command reports. */
    public interface ConfigReload {
        void run() throws IOException;
    }

    public void onConfigReload(ConfigReload action) {
        this.configReload = action;
    }

    private Command configCommand() {
        Command command = admin("config", "kiln.config",
                (sender, context) -> Chat.say(sender, "<gray>Usage: /kiln config reload</gray>"));
        command.addSubcommand(admin("reload", "kiln.config", (sender, context) -> {
            try {
                configReload.run();
                Chat.say(sender, "<green>kiln.yml reloaded.</green> <gray>Name, colors, server list, chat and tab list "
                        + "use the new values; ports, storage and worlds change with a restart.</gray>");
            } catch (IOException | RuntimeException e) {
                Chat.error(sender, "kiln.yml could not be read, nothing changed: " + e.getMessage());
            }
        }));
        return command;
    }

    private Command profileCommand() {
        ArgumentInteger seconds = ArgumentType.Integer("seconds");
        seconds.between(1, 300);
        Profiler profiler = new Profiler(() -> {
            Map<String, String> packages = new HashMap<>();
            for (PluginManager.Loaded loaded : plugins.loaded()) {
                String main = loaded.info().main();
                int dot = main.lastIndexOf('.');
                packages.put(dot < 0 ? main : main.substring(0, dot + 1), loaded.info().name());
            }
            return packages;
        });
        Command command = admin("profile", "kiln.profile", (sender, context) -> profiler.run(sender, 10));
        command.addSyntax((sender, context) -> profiler.run(sender, context.get(seconds)), seconds);
        return command;
    }

    private Command installCommand() {
        ArgumentWord source = ArgumentType.Word("source");
        PluginInstaller installer = new PluginInstaller(Path.of("plugins"));
        Command command = admin("install", "kiln.install",
                (sender, context) -> Chat.say(sender, "<gray>Usage: /kiln install [owner/repo or https link to a jar]</gray>"));
        command.addSyntax((sender, context) -> Thread.ofVirtual().start(() -> {
            Chat.say(sender, "<gray>Downloading " + MiniMessage.miniMessage().escapeTags(context.get(source)) + "...</gray>");
            try {
                PluginInstaller.Installed installed = installer.install(context.get(source));
                Chat.say(sender, "<green>" + installed.name() + " " + installed.version() + " downloaded.</green>");
                boolean present = plugins.loaded().stream().anyMatch(loaded -> loaded.info().name().equals(installed.name()));
                MinecraftServer.getSchedulerManager().scheduleNextTick(() -> {
                    if (present) {
                        reload(sender, installed.name());
                    } else {
                        load(sender);
                    }
                });
            } catch (IOException e) {
                Chat.error(sender, "Could not install it: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }), source);
        return command;
    }

    /** A native Minecraft dialog with the most used commands as buttons, for operators in game. */
    private void openMenu(Player player) {
        int online = MinecraftServer.getConnectionManager().getOnlinePlayers().size();
        Component summary = MiniMessage.miniMessage().deserialize("<gray>Kiln " + version + " · " + online
                + (online == 1 ? " player" : " players") + " online · " + plugins.loaded().size() + " plugins · "
                + "maintenance " + (maintenance.enabled() ? "<yellow>on</yellow>" : "off") + "</gray>");
        player.showDialog(menu(summary, maintenance.enabled()));
    }

    static Dialog menu(Component summary, boolean maintenanceOn) {
        List<DialogActionButton> buttons = new ArrayList<>();
        button(buttons, "Plugins", "kiln plugins");
        button(buttons, "Performance", "kiln performance");
        button(buttons, "Players", "kiln players");
        button(buttons, "Servers", "kiln servers");
        button(buttons, maintenanceOn ? "Maintenance off" : "Maintenance on",
                maintenanceOn ? "kiln maintenance off" : "kiln maintenance on");
        button(buttons, "Reload kiln.yml", "kiln config reload");
        button(buttons, "Resend pack", "kiln pack");
        button(buttons, "Profile 10 s", "kiln profile 10");
        DialogMetadata metadata = new DialogMetadata(Chat.title(), Chat.title(), true, false, DialogAfterAction.CLOSE,
                List.of(new DialogBody.PlainMessage(summary, DialogBody.PlainMessage.DEFAULT_WIDTH)), List.of());
        return new Dialog.MultiAction(metadata, buttons, null, 2);
    }

    private static void button(List<DialogActionButton> buttons, String label, String command) {
        buttons.add(new DialogActionButton(Component.text(label), Component.text("/" + command),
                DialogActionButton.DEFAULT_WIDTH, new DialogAction.RunCommand("/" + command)));
    }
}
