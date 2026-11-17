package dev.vensin.kiln.server.plugin;

import dev.vensin.kiln.api.Events;
import dev.vensin.kiln.api.Sidebars;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.scoreboard.Sidebar;

final class PluginSidebars implements Sidebars {

    static final int MAX_LINES = 15;

    private final Map<UUID, Shown> shown = new ConcurrentHashMap<>();

    PluginSidebars(Events events) {
        events.on(PlayerDisconnectEvent.class, event -> shown.remove(event.getPlayer().getUuid()));
    }

    @Override
    public void show(Player player, Component title, List<Component> lines) {
        Shown current = shown.computeIfAbsent(player.getUuid(), id -> {
            Sidebar sidebar = new Sidebar(title);
            sidebar.addViewer(player);
            return new Shown(player, sidebar);
        });
        current.update(title, lines.subList(0, Math.min(lines.size(), MAX_LINES)));
    }

    @Override
    public void hide(Player player) {
        Shown removed = shown.remove(player.getUuid());
        if (removed != null) {
            removed.sidebar.removeViewer(player);
        }
    }

    void hideAll() {
        shown.values().forEach(entry -> entry.sidebar.removeViewer(entry.player));
        shown.clear();
    }

    private static final class Shown {

        private final Player player;
        private final Sidebar sidebar;
        private int count;

        private Shown(Player player, Sidebar sidebar) {
            this.player = player;
            this.sidebar = sidebar;
        }

        private synchronized void update(Component title, List<Component> lines) {
            if (!sidebar.getTitle().equals(title)) {
                sidebar.setTitle(title);
            }
            // Ids follow the position, and the score counts down so the first line sits on top.
            for (int i = 0; i < lines.size(); i++) {
                String id = "line-" + i;
                int score = lines.size() - i;
                Sidebar.ScoreboardLine line = sidebar.getLine(id);
                if (line == null) {
                    sidebar.createLine(new Sidebar.ScoreboardLine(id, lines.get(i), score, Sidebar.NumberFormat.blank()));
                    continue;
                }
                if (!line.getContent().equals(lines.get(i))) {
                    sidebar.updateLineContent(id, lines.get(i));
                }
                if (line.getLine() != score) {
                    sidebar.updateLineScore(id, score);
                }
            }
            for (int i = lines.size(); i < count; i++) {
                sidebar.removeLine("line-" + i);
            }
            count = lines.size();
        }
    }
}
