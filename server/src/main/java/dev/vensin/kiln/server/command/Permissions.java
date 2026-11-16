package dev.vensin.kiln.server.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minestom.server.command.CommandSender;
import net.minestom.server.entity.Player;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Groups of permissions in permissions.yml. Every player is in "default" without being listed. */
public final class Permissions {

    public static final String DEFAULT_GROUP = "default";
    private static final String HEADER = """
            # Groups and their permissions. A group can inherit the permissions of others.
            # A trailing .* grants everything below it, for example build.* grants build.place and build.break.
            # Every player is in "default"; list players under players to give them more groups.
            # Operators (ops.txt) hold every permission anyway.
            """;
    private static final String STARTER = """
            groups:
              default:
                permissions: []
              moderator:
                inherits: [default]
                permissions: [kiln.players, kiln.maintenance]
            players: {}
            """;

    private record Group(Set<String> permissions, List<String> inherits) {}

    private final Path file;
    private final Operators operators;
    private volatile Map<String, Group> groups = Map.of();
    private volatile Map<String, Set<String>> players = Map.of();

    public Permissions(Path file, Operators operators) throws IOException {
        this.file = file;
        this.operators = operators;
        if (!Files.exists(file)) {
            Files.writeString(file, HEADER + STARTER, StandardCharsets.UTF_8);
        }
        reload();
    }

    @SuppressWarnings("unchecked")
    public synchronized void reload() throws IOException {
        Object yaml = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(file, StandardCharsets.UTF_8));
        Map<String, Object> root = yaml instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        Map<String, Group> readGroups = new TreeMap<>();
        if (root.get("groups") instanceof Map<?, ?> section) {
            section.forEach((name, value) -> {
                Map<String, Object> group = value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
                readGroups.put(String.valueOf(name), new Group(new TreeSet<>(strings(group.get("permissions"))),
                        strings(group.get("inherits"))));
            });
        }
        readGroups.putIfAbsent(DEFAULT_GROUP, new Group(new TreeSet<>(), List.of()));
        Map<String, Set<String>> readPlayers = new TreeMap<>();
        if (root.get("players") instanceof Map<?, ?> section) {
            section.forEach((name, value) -> readPlayers.put(String.valueOf(name).toLowerCase(Locale.ROOT),
                    new TreeSet<>(strings(value))));
        }
        groups = readGroups;
        players = readPlayers;
    }

    public boolean has(CommandSender sender, String permission) {
        if (operators.allows(sender)) {
            return true;
        }
        if (!(sender instanceof Player player)) {
            return false;
        }
        return grants(granted(player.getUsername()), permission);
    }

    /** Allows the permission itself, "*" and any parent ending in ".*". */
    static boolean grants(Set<String> held, String permission) {
        if (held.contains(permission) || held.contains("*")) {
            return true;
        }
        for (int dot = permission.lastIndexOf('.'); dot > 0; dot = permission.lastIndexOf('.', dot - 1)) {
            if (held.contains(permission.substring(0, dot) + ".*")) {
                return true;
            }
        }
        return false;
    }

    /** Everything the player's groups grant, following inherits and ignoring loops; package-private for tests. */
    Set<String> grantedTo(String username) {
        return granted(username);
    }

    public Set<String> groupNames() {
        return groups.keySet();
    }

    public Set<String> groupsOf(String username) {
        Set<String> result = new TreeSet<>(players.getOrDefault(username.toLowerCase(Locale.ROOT), Set.of()));
        result.add(DEFAULT_GROUP);
        return result;
    }

    public synchronized boolean give(String username, String group) throws IOException {
        if (!groups.containsKey(group)) {
            throw new IllegalArgumentException("there is no group " + group + " in permissions.yml");
        }
        Map<String, Set<String>> changed = new TreeMap<>(players);
        boolean added = changed.computeIfAbsent(username.toLowerCase(Locale.ROOT), name -> new TreeSet<>()).add(group);
        players = changed;
        save();
        return added;
    }

    public synchronized boolean take(String username, String group) throws IOException {
        Map<String, Set<String>> changed = new TreeMap<>(players);
        Set<String> own = changed.get(username.toLowerCase(Locale.ROOT));
        boolean removed = own != null && own.remove(group);
        if (own != null && own.isEmpty()) {
            changed.remove(username.toLowerCase(Locale.ROOT));
        }
        players = changed;
        save();
        return removed;
    }

    /** Everything the player's groups grant, following inherits and ignoring loops. */
    private Set<String> granted(String username) {
        Set<String> held = new HashSet<>();
        Set<String> seen = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>(groupsOf(username));
        while (!todo.isEmpty()) {
            String name = todo.pop();
            Group group = groups.get(name);
            if (group != null && seen.add(name)) {
                held.addAll(group.permissions());
                todo.addAll(group.inherits());
            }
        }
        return held;
    }

    private void save() throws IOException {
        Map<String, Object> groupSection = new LinkedHashMap<>();
        groups.forEach((name, group) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            if (!group.inherits().isEmpty()) {
                entry.put("inherits", group.inherits());
            }
            entry.put("permissions", List.copyOf(group.permissions()));
            groupSection.put(name, entry);
        });
        Map<String, Object> playerSection = new LinkedHashMap<>();
        players.forEach((name, own) -> playerSection.put(name, List.copyOf(own)));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("groups", groupSection);
        root.put("players", playerSection);
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        Files.writeString(file, HEADER + new Yaml(options).dump(root), StandardCharsets.UTF_8);
    }

    private static List<String> strings(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return value == null ? List.of() : List.of(String.valueOf(value));
    }
}
