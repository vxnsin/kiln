package dev.vensin.kiln.api.store;

import net.minestom.server.entity.Player;

public interface PlayerData<T> {

    /** The loaded value of an online player. Change it in place; it is saved when the player leaves. */
    T of(Player player);

    /** Saves right now, for changes that must not wait for the player to leave. */
    void save(Player player);
}
