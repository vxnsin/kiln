package dev.vensin.kiln.server.round;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Every round plugins have going, for /stats, and the hook for stop-after-round. */
public final class RoundBoard {

    public interface Live {
        String plugin();

        String stateName();

        int playerCount();

        int minPlayers();
    }

    private final Set<Live> rounds = ConcurrentHashMap.newKeySet();
    private volatile Consumer<Live> whenEnded = round -> {};

    public void add(Live round) {
        rounds.add(round);
    }

    /** Dropped without an end, for a plugin that is unloaded. */
    public void remove(Live round) {
        rounds.remove(round);
    }

    public void ended(Live round) {
        if (rounds.remove(round)) {
            whenEnded.accept(round);
        }
    }

    public List<Live> list() {
        return List.copyOf(rounds);
    }

    public void whenEnded(Consumer<Live> action) {
        this.whenEnded = action;
    }
}
