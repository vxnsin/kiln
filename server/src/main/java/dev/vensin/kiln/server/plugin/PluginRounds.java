package dev.vensin.kiln.server.plugin;

import dev.vensin.kiln.api.Round;
import dev.vensin.kiln.api.Rounds;
import dev.vensin.kiln.server.command.ServerTexts;
import dev.vensin.kiln.server.round.RoundBoard;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.timer.Task;

final class PluginRounds implements Rounds {

    private static final Duration SECOND = Duration.ofSeconds(1);
    private static final Sound TICK = Sound.sound(Key.key("block.note_block.hat"), Sound.Source.MASTER, 1f, 1f);
    private static final Sound GO = Sound.sound(Key.key("entity.player.levelup"), Sound.Source.MASTER, 1f, 1f);

    private final String plugin;
    private final PluginTasks tasks;
    private final RoundBoard board;
    private final Set<GameRound> rounds = ConcurrentHashMap.newKeySet();

    PluginRounds(String plugin, PluginTasks tasks, RoundBoard board) {
        this.plugin = plugin;
        this.tasks = tasks;
        this.board = board;
    }

    @Override
    public Round create(Instance world) {
        GameRound round = new GameRound(world);
        rounds.add(round);
        board.add(round);
        round.task = tasks.every(SECOND, round::tick);
        return round;
    }

    /** The timers go with the plugin's other tasks; this only takes the rounds off the board. */
    void closeAll() {
        rounds.forEach(board::remove);
        rounds.clear();
    }

    private static Component text(String key, String... pairs) {
        List<TagResolver> resolvers = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            resolvers.add(Placeholder.unparsed(pairs[i], pairs[i + 1]));
        }
        return MiniMessage.miniMessage().deserialize(ServerTexts.line(key), TagResolver.resolver(resolvers));
    }

    private final class GameRound implements Round, RoundBoard.Live {

        private final Instance world;
        private volatile State state = State.WAITING;
        private volatile int minPlayers = 2;
        private volatile int countdown = 10;
        private int left;
        private volatile Consumer<List<Player>> onStart = players -> {};
        private volatile Consumer<List<Player>> onEnd = players -> {};
        private volatile Task task;

        private GameRound(Instance world) {
            this.world = world;
        }

        @Override
        public Round minPlayers(int count) {
            this.minPlayers = Math.max(1, count);
            return this;
        }

        @Override
        public Round countdown(Duration duration) {
            this.countdown = (int) Math.max(0, duration.toSeconds());
            return this;
        }

        @Override
        public Round onStart(Consumer<List<Player>> action) {
            this.onStart = action;
            return this;
        }

        @Override
        public Round onEnd(Consumer<List<Player>> action) {
            this.onEnd = action;
            return this;
        }

        private synchronized void tick() {
            List<Player> players = players();
            switch (state) {
                case WAITING -> {
                    if (players.size() >= minPlayers) {
                        state = State.STARTING;
                        left = countdown;
                        showCountdown(players);
                    } else if (!players.isEmpty()) {
                        Component waiting = text("round-waiting", "count", String.valueOf(players.size()),
                                "needed", String.valueOf(minPlayers));
                        players.forEach(player -> player.sendActionBar(waiting));
                    }
                }
                case STARTING -> {
                    if (players.size() < minPlayers) {
                        state = State.WAITING;
                        players.forEach(Player::clearTitle);
                    } else {
                        left--;
                        showCountdown(players);
                    }
                }
                case RUNNING -> {
                    if (players.isEmpty()) {
                        end();
                    }
                }
                case ENDED -> {}
            }
        }

        private void showCountdown(List<Player> players) {
            if (left <= 0) {
                start();
                return;
            }
            Component starting = text("round-starting", "seconds", String.valueOf(left));
            for (Player player : players) {
                player.sendActionBar(starting);
                if (left <= 5) {
                    player.showTitle(Title.title(Component.text(left), Component.empty(),
                            Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ZERO)));
                    player.playSound(TICK);
                }
            }
        }

        @Override
        public synchronized void start() {
            if (state == State.RUNNING || state == State.ENDED) {
                return;
            }
            state = State.RUNNING;
            List<Player> players = players();
            Component go = text("round-go");
            for (Player player : players) {
                player.sendActionBar(Component.empty());
                player.showTitle(Title.title(go, Component.empty(),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(1000), Duration.ofMillis(300))));
                player.playSound(GO);
            }
            onStart.accept(players);
        }

        @Override
        public synchronized void end() {
            if (state == State.ENDED) {
                return;
            }
            state = State.ENDED;
            Task running = task;
            if (running != null) {
                running.cancel();
            }
            rounds.remove(this);
            try {
                onEnd.accept(players());
            } finally {
                board.ended(this);
            }
        }

        @Override
        public State state() {
            return state;
        }

        @Override
        public Instance world() {
            return world;
        }

        @Override
        public List<Player> players() {
            return List.copyOf(world.getPlayers());
        }

        @Override
        public String plugin() {
            return plugin;
        }

        @Override
        public String stateName() {
            return state.name().toLowerCase(Locale.ROOT);
        }

        @Override
        public int playerCount() {
            return world.getPlayers().size();
        }

        @Override
        public int minPlayers() {
            return minPlayers;
        }
    }
}
