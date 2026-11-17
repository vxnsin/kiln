package dev.vensin.kiln.server.plugin;

import dev.vensin.kiln.api.Events;
import dev.vensin.kiln.api.Hologram;
import dev.vensin.kiln.api.Holograms;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.metadata.display.AbstractDisplayMeta;
import net.minestom.server.entity.metadata.display.TextDisplayMeta;
import net.minestom.server.event.instance.RemoveEntityFromInstanceEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.instance.Instance;

/** One text display per viewer, visible to that viewer only, so each player can get their own text. */
final class PluginHolograms implements Holograms {

    private final Set<PlayerHologram> holograms = ConcurrentHashMap.newKeySet();

    PluginHolograms(Events events) {
        events.on(PlayerSpawnEvent.class, event -> holograms.stream()
                .filter(hologram -> hologram.world == event.getInstance())
                .forEach(hologram -> hologram.show(event.getPlayer())));
        events.on(RemoveEntityFromInstanceEvent.class, event -> {
            if (event.getEntity() instanceof Player player) {
                holograms.forEach(hologram -> hologram.hide(player));
            }
        });
    }

    @Override
    public Hologram create(Instance world, Pos position, Function<Player, Component> text) {
        PlayerHologram hologram = new PlayerHologram(world, position, text);
        holograms.add(hologram);
        world.getPlayers().forEach(hologram::show);
        return hologram;
    }

    void removeAll() {
        List.copyOf(holograms).forEach(PlayerHologram::remove);
    }

    private final class PlayerHologram implements Hologram {

        private final Instance world;
        private final Map<UUID, Entity> shown = new ConcurrentHashMap<>();
        private volatile Pos position;
        private volatile Function<Player, Component> text;

        private PlayerHologram(Instance world, Pos position, Function<Player, Component> text) {
            this.world = world;
            this.position = position;
            this.text = text;
        }

        @Override
        public void show(Player player) {
            if (player.getInstance() != world || shown.containsKey(player.getUuid())) {
                return;
            }
            Entity entity = new Entity(EntityType.TEXT_DISPLAY);
            entity.setNoGravity(true);
            entity.setAutoViewable(false);
            TextDisplayMeta meta = (TextDisplayMeta) entity.getEntityMeta();
            meta.setBillboardRenderConstraints(AbstractDisplayMeta.BillboardConstraints.CENTER);
            meta.setShadow(true);
            meta.setBackgroundColor(0);
            meta.setText(text.apply(player));
            shown.put(player.getUuid(), entity);
            entity.setInstance(world, position).thenRun(() -> entity.addViewer(player));
        }

        @Override
        public void hide(Player player) {
            Entity entity = shown.remove(player.getUuid());
            if (entity != null) {
                entity.remove();
            }
        }

        @Override
        public void refresh() {
            for (Player player : world.getPlayers()) {
                Entity entity = shown.get(player.getUuid());
                if (entity == null) {
                    continue;
                }
                TextDisplayMeta meta = (TextDisplayMeta) entity.getEntityMeta();
                Component fresh = text.apply(player);
                if (!fresh.equals(meta.getText())) {
                    meta.setText(fresh);
                }
            }
        }

        @Override
        public void text(Function<Player, Component> text) {
            this.text = text;
            refresh();
        }

        @Override
        public void teleport(Pos position) {
            this.position = position;
            shown.values().forEach(entity -> entity.teleport(position));
        }

        @Override
        public void remove() {
            holograms.remove(this);
            shown.values().forEach(Entity::remove);
            shown.clear();
        }
    }
}
