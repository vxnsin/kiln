package dev.vensin.kiln.server.plugin;

import dev.vensin.kiln.api.Events;
import java.util.function.Consumer;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;

final class PluginEvents implements Events {

    private final EventNode<Event> node;
    private final PluginTiming timing;

    PluginEvents(EventNode<Event> node, PluginTiming timing) {
        this.node = node;
        this.timing = timing;
    }

    @Override
    public <E extends Event> void on(Class<E> type, Consumer<E> handler) {
        node.addListener(type, event -> {
            long start = System.nanoTime();
            try {
                handler.accept(event);
            } finally {
                timing.add(System.nanoTime() - start);
            }
        });
    }

    @Override
    public EventNode<Event> node() {
        return node;
    }
}
