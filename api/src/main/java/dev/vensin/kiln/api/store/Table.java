package dev.vensin.kiln.api.store;

import java.util.Optional;
import java.util.Set;

/** Calls block until the storage answers. Off the tick thread that is fine; on it, keep them rare. */
public interface Table<T> {

    Optional<T> get(String key);

    void put(String key, T value);

    boolean remove(String key);

    Set<String> keys();
}
