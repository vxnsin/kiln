package dev.vensin.kiln.server.command;

import dev.vensin.kiln.server.world.WorldFiles;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.arguments.ArgumentWord;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;

/** Getting around while building and testing: game modes, teleporting, and visiting maps from worlds/. */
final class TravelCommands {

    private static final Map<String, GameMode> MODES = Map.of("survival", GameMode.SURVIVAL, "creative",
            GameMode.CREATIVE, "adventure", GameMode.ADVENTURE, "spectator", GameMode.SPECTATOR);
    private static final Duration EMPTY_CHECK = Duration.ofSeconds(30);

    private final Permissions permissions;
    private final WorldFiles worlds;
    /** One copy per map, shared by everyone visiting it and closed once it has been empty for a while. */
    private final Map<String, Instance> visited = new ConcurrentHashMap<>();

    TravelCommands(Permissions permissions, WorldFiles worlds) {
        this.permissions = permissions;
        this.worlds = worlds;
        MinecraftServer.getSchedulerManager().buildTask(this::closeEmpty).repeat(EMPTY_CHECK).schedule();
    }

    List<Command> commands() {
        return List.of(gamemode(), teleport(), world());
    }

    private Command gamemode() {
        ArgumentWord mode = ArgumentType.Word("mode").from(MODES.keySet().toArray(String[]::new));
        ArgumentWord target = players("player");
        Command command = guarded("gm", "kiln.gm");
        command.setDefaultExecutor((sender, context) -> Chat.say(sender,
                "<gray>Usage: /kiln gm [survival|creative|adventure|spectator] [player]</gray>"));
        command.addSyntax((sender, context) -> setMode(sender, context.get(mode), null), mode);
        command.addSyntax((sender, context) -> setMode(sender, context.get(mode), context.get(target)), mode, target);
        return command;
    }

    private void setMode(CommandSender sender, String mode, String targetName) {
        Player target = targetName == null ? (sender instanceof Player self ? self : null) : online(targetName);
        if (target == null) {
            Chat.error(sender, targetName == null ? "Name a player from the console." : targetName + " is not online.");
            return;
        }
        target.setGameMode(MODES.get(mode.toLowerCase(Locale.ROOT)));
        Chat.say(sender, "<green>" + target.getUsername() + " is now in " + mode + ".</green>");
    }

    private Command teleport() {
        ArgumentWord first = players("player");
        ArgumentWord second = players("target");
        Command command = guarded("tp", "kiln.tp");
        command.setDefaultExecutor((sender, context) -> Chat.say(sender, "<gray>Usage: /kiln tp [player] or [player] [target]</gray>"));
        command.addSyntax((sender, context) -> {
            if (sender instanceof Player self) {
                move(sender, self, context.get(first));
            } else {
                Chat.error(sender, "From the console, name who goes where: /kiln tp [player] [target]");
            }
        }, first);
        command.addSyntax((sender, context) -> {
            Player who = online(context.get(first));
            if (who == null) {
                Chat.error(sender, context.get(first) + " is not online.");
            } else {
                move(sender, who, context.get(second));
            }
        }, first, second);
        return command;
    }

    private void move(CommandSender sender, Player who, String targetName) {
        Player target = online(targetName);
        if (target == null) {
            Chat.error(sender, targetName + " is not online.");
            return;
        }
        if (who.getInstance() == target.getInstance()) {
            who.teleport(target.getPosition());
        } else {
            who.setInstance(target.getInstance(), target.getPosition());
        }
        Chat.say(sender, "<green>" + who.getUsername() + " went to " + target.getUsername() + ".</green>");
    }

    private Command world() {
        ArgumentWord name = ArgumentType.Word("world");
        name.setSuggestionCallback((sender, context, suggestion) -> {
            suggestion.addEntry(new SuggestionEntry("home"));
            worlds.available().forEach(world -> suggestion.addEntry(new SuggestionEntry(world)));
        });
        Command command = guarded("world", "kiln.world");
        command.setDefaultExecutor((sender, context) -> {
            List<String> available = worlds.available();
            Chat.say(sender, available.isEmpty() ? "<gray>No worlds yet. Put world folders into worlds/.</gray>"
                    : "<white>Worlds</white> <gray>" + String.join(", ", available) + "</gray>");
            Chat.item(sender, "<gold>/kiln world [name]</gold> <gray>visit one,</gray> <gold>/kiln world home</gold> <gray>come back</gray>");
        });
        command.addSyntax((sender, context) -> visit(sender, context.get(name)), name);
        return command;
    }

    private void visit(CommandSender sender, String name) {
        if (!(sender instanceof Player player)) {
            Chat.error(sender, "Only players can travel.");
            return;
        }
        if (name.equals("home")) {
            if (worlds.home() == null) {
                Chat.error(sender, "There is no default world; a plugin decides where players are.");
                return;
            }
            player.setInstance(worlds.home(), worlds.homeSpawn());
            return;
        }
        if (!worlds.available().contains(name)) {
            Chat.error(sender, "There is no world " + name + " in worlds/.");
            return;
        }
        Instance instance = visited.computeIfAbsent(name, worlds::open);
        player.setInstance(instance, worlds.spawn(name));
        Chat.say(sender, "<green>Welcome to " + name + ".</green> <gray>Changes stay in this copy; the files are untouched.</gray>");
    }

    private void closeEmpty() {
        visited.entrySet().removeIf(entry -> {
            if (entry.getValue().getPlayers().isEmpty()) {
                worlds.close(entry.getValue());
                return true;
            }
            return false;
        });
    }

    private Command guarded(String name, String permission) {
        Command command = new Command(name);
        command.setCondition((sender, input) -> permissions.has(sender, permission));
        return command;
    }

    private static ArgumentWord players(String id) {
        ArgumentWord argument = ArgumentType.Word(id);
        argument.setSuggestionCallback((sender, context, suggestion) -> MinecraftServer.getConnectionManager()
                .getOnlinePlayers().forEach(player -> suggestion.addEntry(new SuggestionEntry(player.getUsername()))));
        return argument;
    }

    private static Player online(String name) {
        return MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(name);
    }
}
