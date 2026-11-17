package dev.vensin.kiln.api;

import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;

/**
 * Floating text in a world, sent to each player on their own: everyone in the world sees it, but every player can
 * read a different text, for example in their language or with their own numbers. Removed when the plugin is
 * unloaded or swapped.
 */
public interface Holograms {

    /** {@code text} is asked once per player when they see it, and again on {@link Hologram#refresh()}. */
    Hologram create(Instance world, Pos position, Function<Player, Component> text);

    /** The same text for everyone. Several lines are one text with {@code Component.newline()} between them. */
    default Hologram create(Instance world, Pos position, Component text) {
        return create(world, position, player -> text);
    }
}
