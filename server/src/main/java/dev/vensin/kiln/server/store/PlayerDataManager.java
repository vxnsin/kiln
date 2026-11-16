package dev.vensin.kiln.server.store;

import dev.vensin.kiln.api.store.PlayerData;
import dev.vensin.kiln.api.store.Table;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads every registered value while a player is still connecting, which happens off the tick thread. A value
 * leaves memory only after it was written, so a storage failure keeps it for the next save instead of losing it.
 */
public final class PlayerDataManager {

    private static final Logger LOG = LoggerFactory.getLogger(PlayerDataManager.class);

    private final List<Loaded<?>> all = new CopyOnWriteArrayList<>();

    public void register(GlobalEventHandler events) {
        events.addListener(AsyncPlayerConfigurationEvent.class, event -> {
            if (event.isFirstConfig()) {
                all.forEach(data -> data.load(event.getPlayer().getUuid()));
            }
        });
        // Plugins see the disconnect after Kiln; unloading on the next tick lets their last changes go along.
        events.addListener(PlayerDisconnectEvent.class, event -> {
            UUID id = event.getPlayer().getUuid();
            MinecraftServer.getSchedulerManager().scheduleNextTick(() -> all.forEach(data -> data.unload(id)));
        });
    }

    public <T> PlayerData<T> create(Table<T> table, Supplier<T> fresh) {
        Loaded<T> data = new Loaded<>(table, fresh);
        all.add(data);
        return data;
    }

    /** Saves what is loaded and stops tracking it, for a plugin that is being unloaded. */
    public void remove(PlayerData<?> data) {
        if (data instanceof Loaded<?> loaded) {
            loaded.saveAll();
            all.remove(loaded);
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
            T value = values.get(id);
            if (value != null && write(id, value)) {
                values.remove(id, value);
            }
        }

        /** Every value gets its own try, so one failing write does not keep the others from being saved. */
        private void saveAll() {
            values.forEach(this::write);
        }

        private boolean write(UUID id, T value) {
            try {
                table.put(id.toString(), value);
                return true;
            } catch (RuntimeException e) {
                LOG.error("Player data for {} could not be saved and stays in memory: {}", id, e.getMessage());
                return false;
            }
        }
    }
}
