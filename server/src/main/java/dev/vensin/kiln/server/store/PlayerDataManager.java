package dev.vensin.kiln.server.store;

import dev.vensin.kiln.api.store.PlayerData;
import dev.vensin.kiln.api.store.Table;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;

/** Loads every registered value while a player is still connecting, which happens off the tick thread. */
public final class PlayerDataManager {

    private final List<Loaded<?>> all = new CopyOnWriteArrayList<>();

    public void register(GlobalEventHandler events) {
        events.addListener(AsyncPlayerConfigurationEvent.class, event -> {
            if (event.isFirstConfig()) {
                all.forEach(data -> data.load(event.getPlayer().getUuid()));
            }
        });
        events.addListener(PlayerDisconnectEvent.class, event -> all.forEach(data -> data.unload(event.getPlayer().getUuid())));
    }

    public <T> PlayerData<T> create(Table<T> table, Supplier<T> fresh) {
        Loaded<T> data = new Loaded<>(table, fresh);
        all.add(data);
        return data;
    }

    /** Saves what is loaded and stops tracking it, for a plugin that is being unloaded. */
    public void remove(PlayerData<?> data) {
        if (data instanceof Loaded<?> loaded && all.remove(loaded)) {
            loaded.saveAll();
        }
    }

    public void saveAll() {
        all.forEach(Loaded::saveAll);
    }

    private static final class Loaded<T> implements PlayerData<T> {

        private final Table<T> table;
        private final Supplier<T> fresh;
        private final Map<UUID, T> values = new ConcurrentHashMap<>();

        private Loaded(Table<T> table, Supplier<T> fresh) {
            this.table = table;
            this.fresh = fresh;
        }

        @Override
        public T of(Player player) {
            return load(player.getUuid());
        }

        @Override
        public void save(Player player) {
            T value = values.get(player.getUuid());
            if (value != null) {
                table.put(player.getUuid().toString(), value);
            }
        }

        private T load(UUID id) {
            return values.computeIfAbsent(id, key -> table.get(key.toString()).orElseGet(fresh));
        }

        private void unload(UUID id) {
            T value = values.remove(id);
            if (value != null) {
                table.put(id.toString(), value);
            }
        }

        private void saveAll() {
            values.forEach((id, value) -> table.put(id.toString(), value));
        }
    }
}
