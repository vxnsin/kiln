package dev.vensin.kiln.server.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vensin.kiln.api.store.Table;
import java.net.URI;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RedisDriverTest {

    record Coins(int amount, String note) {}

    @Test
    void storesReadsListsAndRemovesValues() throws Exception {
        try (FakeRedis redis = new FakeRedis();
             RedisDriver driver = new RedisDriver(URI.create("redis://user:p%40ss@127.0.0.1:" + redis.port() + "/2"))) {
            Table<Coins> coins = driver.forPlugin("shop").table("coins", Coins.class);
            coins.put("alex", new Coins(30, "Grüße"));
            coins.put("sam", new Coins(5, ""));

            assertEquals(Optional.of(new Coins(30, "Grüße")), coins.get("alex"));
            assertEquals(Optional.empty(), coins.get("nobody"));
            assertEquals(Set.of("alex", "sam"), coins.keys());
            assertTrue(redis.data.containsKey("kiln:shop:coins:alex"));
            assertTrue(coins.remove("sam"));
            assertFalse(coins.remove("sam"));

            assertEquals("AUTH user p@ss", redis.commands.get(0), "credentials come from the URI, decoded");
            assertEquals("SELECT 2", redis.commands.get(1));
        }
    }
}
