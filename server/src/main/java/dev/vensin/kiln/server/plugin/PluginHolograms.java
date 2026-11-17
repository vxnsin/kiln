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
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.MetadataDef;
import net.minestom.server.entity.MetadataHolder;
import net.minestom.server.entity.Player;
import net.minestom.server.event.instance.RemoveEntityFromInstanceEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.network.packet.server.play.DestroyEntitiesPacket;
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket;
import net.minestom.server.network.packet.server.play.EntityTeleportPacket;
import net.minestom.server.network.packet.server.play.SpawnEntityPacket;

/**
 * Packets only: the server holds no entity, nothing is ticked, and each player is sent their own text under the
 * same entity id, which no real entity uses.
 */
final class PluginHolograms implements Holograms {

    private static final byte BILLBOARD_CENTER = 3;

    private final Set<PacketHologram> holograms = ConcurrentHashMap.newKeySet();

    PluginHolograms(Events events) {
        events.on(PlayerSpawnEvent.class, event -> holograms.stream()
                .filter(hologram -> hologram.world == event.getInstance())
                .forEach(hologram -> hologram.show(event.getPlayer())));
        // The client drops all entities when it changes worlds, so only the bookkeeping has to follow.
        events.on(RemoveEntityFromInstanceEvent.class, event -> {
            if (event.getEntity() instanceof Player player) {
                holograms.forEach(hologram -> hologram.viewers.remove(player.getUuid()));
            }
        });
    }

    @Override
    public Hologram create(Instance world, Pos position, Function<Player, Component> text) {
        PacketHologram hologram = new PacketHologram(world, position, text);
        holograms.add(hologram);
        world.getPlayers().forEach(hologram::show);
        return hologram;
    }

    void removeAll() {
        List.copyOf(holograms).forEach(PacketHologram::remove);
    }

    static Map<Integer, net.minestom.server.entity.Metadata.Entry<?>> metadata(Component text, boolean full) {
        MetadataHolder holder = new MetadataHolder(entries -> {});
        holder.setNotifyAboutChanges(false);
        if (full) {
            holder.set(MetadataDef.TextDisplay.BILLBOARD_CONSTRAINTS, BILLBOARD_CENTER);
            holder.set(MetadataDef.TextDisplay.HAS_SHADOW, true);
            holder.set(MetadataDef.TextDisplay.BACKGROUND_COLOR, 0);
        }
        holder.set(MetadataDef.TextDisplay.TEXT, text);
        return holder.getEntries();
    }

    private record Viewer(Player player, Component text) {}

    private final class PacketHologram implements Hologram {

        private final int id = Entity.generateId();
        private final UUID uuid = UUID.randomUUID();
        private final Instance world;
        private final Map<UUID, Viewer> viewers = new ConcurrentHashMap<>();
        private volatile Pos position;
        private volatile Function<Player, Component> text;

        private PacketHologram(Instance world, Pos position, Function<Player, Component> text) {
            this.world = world;
            this.position = position;
            this.text = text;
        }

        @Override
        public void show(Player player) {
            if (player.getInstance() != world || viewers.containsKey(player.getUuid())) {
                return;
            }
            Component own = text.apply(player);
            viewers.put(player.getUuid(), new Viewer(player, own));
            player.sendPacket(new SpawnEntityPacket(id, uuid, EntityType.TEXT_DISPLAY, position, 0f, 0, Vec.ZERO));
            player.sendPacket(new EntityMetaDataPacket(id, metadata(own, true)));
        }

        @Override
        public void hide(Player player) {
            if (viewers.remove(player.getUuid()) != null) {
                player.sendPacket(new DestroyEntitiesPacket(id));
            }
        }

        @Override
        public void refresh() {
            viewers.replaceAll((key, viewer) -> {
                Component fresh = text.apply(viewer.player());
                if (fresh.equals(viewer.text())) {
                    return viewer;
                }
                viewer.player().sendPacket(new EntityMetaDataPacket(id, metadata(fresh, false)));
                return new Viewer(viewer.player(), fresh);
            });
        }

        @Override
        public void text(Function<Player, Component> text) {
            this.text = text;
            refresh();
        }

        @Override
        public void teleport(Pos position) {
            this.position = position;
            EntityTeleportPacket packet = new EntityTeleportPacket(id, position, Vec.ZERO, 0, false);
            viewers.values().forEach(viewer -> viewer.player().sendPacket(packet));
        }

        @Override
        public void remove() {
            holograms.remove(this);
            DestroyEntitiesPacket packet = new DestroyEntitiesPacket(id);
            viewers.values().forEach(viewer -> viewer.player().sendPacket(packet));
            viewers.clear();
        }
    }
}
