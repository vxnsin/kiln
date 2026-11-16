package dev.vensin.kiln.api;

import java.util.function.Consumer;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;

/** A plugin's listeners. They are timed for /kiln performance and removed when the plugin is unloaded or swapped. */
public interface Events {

    <E extends Event> void on(Class<E> type, Consumer<E> handler);

    /**
     * The plugin's own Minestom event node, for filters, priorities and child nodes. Everything on it is removed
     * with the plugin as well, but only listeners added through {@link #on} show up in /kiln performance.
     */
    EventNode<Event> node();
}
