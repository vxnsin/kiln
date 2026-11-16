package dev.vensin.kiln.server.command;

import dev.vensin.kiln.api.store.Table;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;

/** While on, only operators and explicitly allowed players get in. The state survives restarts. */
public final class Maintenance {

    /** Stored as JSON, so it has to stay a plain class with fields. */
    public static final class State {
        boolean enabled;
        Set<String> allowed = new TreeSet<>();
    }

    private static final String KEY = "maintenance";

    private final Table<State> table;
    private final Operators operators;
    private final State state;

    public Maintenance(Table<State> table, Operators operators) {
        this.table = table;
        this.operators = operators;
        this.state = table.get(KEY).orElseGet(State::new);
    }

    public synchronized boolean enabled() {
        return state.enabled;
    }

    public synchronized Set<String> allowed() {
        return Set.copyOf(state.allowed);
    }

    public synchronized boolean lets(String username) {
        return !state.enabled || operators.isOperator(username) || state.allowed.contains(lower(username));
    }

    public Component screen() {
        return ServerTexts.screen("maintenance");
    }

    /** Turning it on sends everyone home who would not be let in again. */
    public synchronized void set(boolean enabled) {
        state.enabled = enabled;
        table.put(KEY, state);
        if (enabled) {
            for (Player player : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
                if (!lets(player.getUsername())) {
                    player.kick(screen());
                }
            }
        }
    }

    public synchronized boolean allow(String username) {
        boolean added = state.allowed.add(lower(username));
        table.put(KEY, state);
        return added;
    }

    public synchronized boolean deny(String username) {
        boolean removed = state.allowed.remove(lower(username));
        table.put(KEY, state);
        return removed;
    }

    private static String lower(String username) {
        return username.toLowerCase(Locale.ROOT);
    }
}
