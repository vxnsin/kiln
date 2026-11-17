package dev.vensin.kiln.api;

import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;

public interface Hologram {

    /** Asks the text again for every player who sees it; only changed texts are sent. */
    void refresh();

    /** Replaces the text for everyone. */
    void text(Function<Player, Component> text);

    void teleport(Pos position);

    /** Takes it away for this player only, until they enter the world again or {@link #show} is called. */
    void hide(Player player);

    void show(Player player);

    void remove();
}
