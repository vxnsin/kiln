package dev.vensin.kiln.server.network;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/** Without Redis there is only this server, so every message comes straight back. */
public final class LocalBus implements Bus {

    private final List<BiConsumer<String, String>> handlers = new CopyOnWriteArrayList<>();

    @Override
    public void publish(String channel, String json) {
        handlers.forEach(handler -> handler.accept(channel, json));
    }

    @Override
    public void listen(BiConsumer<String, String> handler) {
        handlers.add(handler);
    }

    @Override
    public String describe() {
        return "this server only";
    }
}
