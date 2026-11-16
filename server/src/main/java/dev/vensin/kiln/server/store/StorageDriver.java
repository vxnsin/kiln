package dev.vensin.kiln.server.store;

import dev.vensin.kiln.api.store.Store;
import java.util.regex.Pattern;

/** Where plugin data lives. Plugins only see {@link Store}, so the driver can change without touching them. */
public interface StorageDriver extends AutoCloseable {

    Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    Store forPlugin(String plugin);

    /** One line for the startup report. */
    String describe();

    @Override
    default void close() {}

    static String safe(String name) {
        if (!SAFE_NAME.matcher(name).matches() || name.startsWith(".")) {
            throw new IllegalArgumentException("names may only use letters, digits, '.', '-' and '_': " + name);
        }
        return name;
    }
}
