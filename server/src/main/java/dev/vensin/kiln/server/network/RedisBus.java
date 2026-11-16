package dev.vensin.kiln.server.network;

import dev.vensin.kiln.server.store.RespClient;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Redis pub/sub on channels starting with kiln:net:, resubscribing whenever the connection drops. */
public final class RedisBus implements Bus {

    private static final Logger LOG = LoggerFactory.getLogger(RedisBus.class);
    private static final String PREFIX = "kiln:net:";
    private static final long RETRY_MILLIS = 3000;

    private final URI uri;
    private final RespClient publisher;
    private final List<BiConsumer<String, String>> handlers = new CopyOnWriteArrayList<>();

    public RedisBus(URI uri) {
        this.uri = uri;
        this.publisher = new RespClient(uri);
        Thread.ofPlatform().daemon().name("network").start(this::receive);
    }

    @Override
    public void publish(String channel, String json) {
        try {
            publisher.call("PUBLISH", PREFIX + channel, json);
        } catch (IOException e) {
            LOG.warn("Message on {} not sent: {}", channel, e.getMessage());
        }
    }

    @Override
    public void listen(BiConsumer<String, String> handler) {
        handlers.add(handler);
    }

    @Override
    public String describe() {
        return "redis pub/sub at " + uri.getHost() + ":" + (uri.getPort() > 0 ? uri.getPort() : 6379);
    }

    private void receive() {
        boolean warned = false;
        while (true) {
            try {
                new RespClient(uri).subscribe(PREFIX + "*", (channel, json) ->
                        handlers.forEach(handler -> handler.accept(channel.substring(PREFIX.length()), json)));
            } catch (IOException e) {
                if (!warned) {
                    LOG.warn("Lost the network connection, retrying: {}", e.getMessage());
                    warned = true;
                }
            }
            try {
                Thread.sleep(RETRY_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
