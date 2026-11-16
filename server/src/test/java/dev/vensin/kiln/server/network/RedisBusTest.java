package dev.vensin.kiln.server.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vensin.kiln.server.store.FakeRedis;
import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RedisBusTest {

    @Test
    void aMessageFromOneServerReachesTheOther() throws Exception {
        try (FakeRedis redis = new FakeRedis()) {
            URI uri = URI.create("redis://127.0.0.1:" + redis.port());
            RedisBus lobby = new RedisBus(uri);
            RedisBus game = new RedisBus(uri);
            BlockingQueue<String> received = new LinkedBlockingQueue<>();
            game.listen((channel, json) -> received.add(channel + " " + json));

            String message = null;
            for (int attempt = 0; attempt < 50 && message == null; attempt++) {
                lobby.publish("plugin:party:invite", "{\"to\":\"alex\"}");
                message = received.poll(100, TimeUnit.MILLISECONDS);
            }
            assertEquals("plugin:party:invite {\"to\":\"alex\"}", message);
        }
    }
}
