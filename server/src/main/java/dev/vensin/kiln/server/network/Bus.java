package dev.vensin.kiln.server.network;

import java.util.function.BiConsumer;

/** Carries messages between servers. Channels are plain strings; payloads are JSON. */
public interface Bus {

    void publish(String channel, String json);

    /** One handler for everything; it receives the channel and the payload. */
    void listen(BiConsumer<String, String> handler);

    /** One line for the startup report. */
    String describe();
}
