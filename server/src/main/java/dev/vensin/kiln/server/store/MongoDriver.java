package dev.vensin.kiln.server.store;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReplaceOptions;
import dev.vensin.kiln.api.store.Store;
import dev.vensin.kiln.api.store.Table;
import java.io.IOException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

/**
 * One collection per plugin table, named &lt;plugin&gt;.&lt;table&gt;, with documents {_id: key, value: ...}.
 * The value is stored as a real document, so it can be read and queried with any MongoDB tool.
 */
public final class MongoDriver implements StorageDriver {

    private static final Gson GSON = new Gson();
    private static final JsonWriterSettings RELAXED = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build();

    private final MongoClient client;
    private final MongoDatabase database;
    private final String host;

    /** Waits five seconds for a server unless the URI says otherwise; the driver's default of 30 feels like a hang. */
    public MongoDriver(String uri) throws IOException {
        ConnectionString connection = new ConnectionString(uri);
        int timeout = connection.getServerSelectionTimeout() != null ? connection.getServerSelectionTimeout() : 5000;
        MongoClientSettings settings = MongoClientSettings.builder().applyConnectionString(connection)
                .applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(timeout, TimeUnit.MILLISECONDS))
                .build();
        this.client = MongoClients.create(settings);
        this.database = client.getDatabase(connection.getDatabase() == null ? "kiln" : connection.getDatabase());
        this.host = String.join(",", connection.getHosts());
        try {
            database.runCommand(new Document("ping", 1));
        } catch (MongoTimeoutException e) {
            client.close();
            throw new IOException("no MongoDB server answered at " + host);
        } catch (MongoException e) {
            client.close();
            throw new IOException(e.getMessage());
        }
    }

    @Override
    public Store forPlugin(String plugin) {
        String prefix = StorageDriver.safe(plugin) + ".";
        return new Store() {
            @Override
            public <T> Table<T> table(String name, Class<T> type) {
                return new MongoTable<>(database.getCollection(prefix + StorageDriver.safe(name)), type);
            }
        };
    }

    @Override
    public String describe() {
        return "mongodb at " + host + "/" + database.getName();
    }

    @Override
    public void close() {
        client.close();
    }

    private record MongoTable<T>(MongoCollection<Document> collection, Class<T> type) implements Table<T> {

        @Override
        public Optional<T> get(String key) {
            Document found = collection.find(Filters.eq("_id", key)).first();
            if (found == null) {
                return Optional.empty();
            }
            JsonObject wrapper = JsonParser.parseString(found.toJson(RELAXED)).getAsJsonObject();
            return Optional.ofNullable(GSON.fromJson(wrapper.get("value"), type));
        }

        @Override
        public void put(String key, T value) {
            Document document = Document.parse("{\"value\": " + GSON.toJson(value, type) + "}").append("_id", key);
            collection.replaceOne(Filters.eq("_id", key), document, new ReplaceOptions().upsert(true));
        }

        @Override
        public boolean remove(String key) {
            return collection.deleteOne(Filters.eq("_id", key)).getDeletedCount() > 0;
        }

        @Override
        public Set<String> keys() {
            Set<String> keys = new HashSet<>();
            collection.find().projection(Projections.include("_id")).forEach(document -> keys.add(document.getString("_id")));
            return Set.copyOf(keys);
        }
    }
}
