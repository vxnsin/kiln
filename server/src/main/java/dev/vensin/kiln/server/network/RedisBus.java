package dev.vensin.kiln.server.network;

import dev.vensin.kiln.server.store.RespClient;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis pub/sub on channels starting with kiln:net:, resubscribing whenever the connection drops. Messages go out
 * on their own thread, so a slow or unreachable Redis never holds up the server tick.
 */
public final class RedisBus implements Bus {

    private static final Logger LOG = LoggerFactory.getLogger(RedisBus.class);
    private static final String PREFIX = "kiln:net:";
    private static final long RETRY_MILLIS = 3000;
    private static final int QUEUE = 1000;

    private final URI uri;
    private final RespClient publisher;
    private final List<BiConsumer<String, String>> handlers = new CopyOnWriteArrayList<>();
    /** When Redis is gone for long, the oldest unsent messages are dropped instead of piling up. */
    private final ThreadPoolExecutor outgoing = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(QUEUE), Thread.ofPlatform().daemon().name("network-out").factory(),
            new ThreadPoolExecutor.DiscardOldestPolicy());

    public RedisBus(URI uri) {
        this.uri = uri;
        this.publisher = new RespClient(uri);
        Thread.ofPlatform().daemon().name("network-in").start(this::receive);
    }

    @Override
    public void publish(String channel, String json) {
        outgoing.execute(() -> {
            try {
                publisher.call("PUBLISH", PREFIX + channel, json);
            } catch (IOException | RuntimeException e) {
                LOG.warn("Message on {} not sent: {}", channel, e.getMessage());
            }
        });
    }

    @Override
    public void listen(BiConsumer<String, String> handler) {
        handlers.add(handler);
    }

    @Override
    public String describe() {
        return "redis pub/sub at " + uri.getHost() + ":" + (uri.getPort() > 0 ? uri.getPort() : 6379);
    }

    /** Gives queued messages, like the goodbye on shutdown, a moment to go out. */
    @Override
    public void close() {
        outgoing.shutdown();
        try {
            outgoing.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        publisher.close();
    }

    private void receive() {
        boolean warned = false;
        while (true) {
            try {
                new RespClient(uri).subscribe(PREFIX + "*", (channel, json) ->
                        handlers.forEach(handler -> handler.accept(channel.substring(PREFIX.length()), json)));
            } catch (IOException | RuntimeException e) {
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
