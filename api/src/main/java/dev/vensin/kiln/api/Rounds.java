package dev.vensin.kiln.api;

import net.minestom.server.instance.Instance;

/** Rounds of a minigame. Running ones are dropped without their end action when the plugin is unloaded. */
public interface Rounds {

    /**
     * A round for the players in {@code world}: it waits until enough of them are there, counts down and starts.
     * Nothing happens until the first player arrives, so it is fine to create it before anyone joined.
     */
    Round create(Instance world);
}
