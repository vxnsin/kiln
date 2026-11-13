package dev.vensin.kiln.api.store;

public interface Store {

    /** Values are stored as JSON, so plain classes and records work without extra code. */
    <T> Table<T> table(String name, Class<T> type);
}
