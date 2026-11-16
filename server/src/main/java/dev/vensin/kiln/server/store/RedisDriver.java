package dev.vensin.kiln.server.store;

import com.google.gson.Gson;
import dev.vensin.kiln.api.store.Store;
import dev.vensin.kiln.api.store.Table;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Values as JSON strings under kiln:&lt;plugin&gt;:&lt;table&gt;:&lt;key&gt;, in Redis, Valkey or Dragonfly. */
public final class RedisDriver implements StorageDriver {

    private static final Gson GSON = new Gson();

    private final URI uri;
    private final RespClient client;

    public RedisDriver(URI uri) throws IOException {
        this.uri = uri;
        this.client = new RespClient(uri);
        client.call("PING");
    }

    public RespClient client() {
        return client;
    }

    public URI uri() {
        return uri;
    }

    @Override
    public Store forPlugin(String plugin) {
        String prefix = "kiln:" + StorageDriver.safe(plugin) + ":";
        return new Store() {
            @Override
            public <T> Table<T> table(String name, Class<T> type) {
                return new RedisTable<>(prefix + StorageDriver.safe(name) + ":", type);
            }
        };
    }

    @Override
    public String describe() {
        return "redis at " + uri.getHost() + ":" + (uri.getPort() > 0 ? uri.getPort() : 6379);
    }

    @Override
    public void close() {
        client.close();
    }

    private final class RedisTable<T> implements Table<T> {

        private final String prefix;
        private final Class<T> type;

        private RedisTable(String prefix, Class<T> type) {
            this.prefix = prefix;
            this.type = type;
        }

        @Override
        public Optional<T> get(String key) {
            Object value = call("GET", prefix + StorageDriver.safe(key));
            return value == null ? Optional.empty() : Optional.ofNullable(GSON.fromJson(String.valueOf(value), type));
        }

        @Override
        public void put(String key, T value) {
            call("SET", prefix + StorageDriver.safe(key), GSON.toJson(value, type));
        }

        @Override
        public boolean remove(String key) {
            return call("DEL", prefix + StorageDriver.safe(key)) instanceof Long deleted && deleted > 0;
        }

        /** SCAN instead of KEYS, so a large database is never blocked while the keys are collected. */
        @Override
        public Set<String> keys() {
            Set<String> keys = new HashSet<>();
            String cursor = "0";
            do {
                List<?> page = (List<?>) call("SCAN", cursor, "MATCH", prefix + "*", "COUNT", "500");
                cursor = String.valueOf(page.get(0));
                for (Object key : (List<?>) page.get(1)) {
                    keys.add(String.valueOf(key).substring(prefix.length()));
                }
            } while (!cursor.equals("0"));
            return Set.copyOf(keys);
        }

        private Object call(String... command) {
            try {
                return client.call(command);
            } catch (IOException e) {
                throw new UncheckedIOException("Redis: " + e.getMessage(), e);
            }
        }
    }
}
