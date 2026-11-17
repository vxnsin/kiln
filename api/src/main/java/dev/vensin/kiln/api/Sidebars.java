package dev.vensin.kiln.api;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.minestom.server.entity.Player;

/** The scoreboard on the right side of the screen, one per player, without the red score numbers. */
public interface Sidebars {

    /**
     * Shows the lines top to bottom, at most 15. Calling it again for the same player updates only what changed,
     * so it is fine to call every second from a timer.
     */
    void show(Player player, Component title, List<Component> lines);

    void hide(Player player);
}
