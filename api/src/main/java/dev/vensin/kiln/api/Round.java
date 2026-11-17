package dev.vensin.kiln.api;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;

/**
 * Waiting, a countdown, the game, the end. Players see how many are missing and the countdown in their action bar;
 * the last five seconds come as a title. A running round with nobody left in its world ends by itself.
 */
public interface Round {

    enum State { WAITING, STARTING, RUNNING, ENDED }

    /** How many players it takes to start the countdown, 2 unless set. Dropping below it stops the countdown. */
    Round minPlayers(int count);

    /** How long the countdown runs, 10 seconds unless set. */
    Round countdown(Duration duration);

    /** Runs once when the countdown is over, with the players in the world at that moment. */
    Round onStart(Consumer<List<Player>> action);

    /** Runs once when the round ends, with the players still in the world. */
    Round onEnd(Consumer<List<Player>> action);

    /** Starts now, without waiting for players or the countdown. */
    void start();

    /** Ends the round. With stop-after-round in kiln.yml, the server stops after a countdown. */
    void end();

    State state();

    Instance world();

    /** The players in the world right now. */
    List<Player> players();
}
