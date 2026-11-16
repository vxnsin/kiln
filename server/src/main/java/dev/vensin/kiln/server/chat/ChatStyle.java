package dev.vensin.kiln.server.chat;

import dev.vensin.kiln.server.command.Permissions;
import java.time.Duration;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerChatEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;

/**
 * Chat lines, join and leave messages and the tab list, all from kiln.yml. An empty template switches that part
 * off. Plugins listen after Kiln, so a plugin that formats chat itself still has the last word.
 */
public final class ChatStyle {

    public record Templates(String chat, String join, String leave, String header, String footer, String serverName,
                            String serverId, int maxPlayers) {}

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final Duration TAB_REFRESH = Duration.ofSeconds(2);

    private final Templates templates;
    private final Permissions permissions;

    public ChatStyle(Templates templates, Permissions permissions) {
        this.templates = templates;
        this.permissions = permissions;
    }

    public void register(GlobalEventHandler events) {
        if (!templates.chat().isBlank()) {
            events.addListener(PlayerChatEvent.class, event -> event.setFormattedMessage(render(templates.chat(),
                    event.getPlayer(), Placeholder.unparsed("message", event.getRawMessage()))));
        }
        events.addListener(PlayerSpawnEvent.class, event -> {
            if (event.isFirstSpawn() && !templates.join().isBlank()) {
                broadcast(render(templates.join(), event.getPlayer()));
            }
        });
        events.addListener(PlayerDisconnectEvent.class, event -> {
            if (!templates.leave().isBlank()) {
                broadcast(render(templates.leave(), event.getPlayer()));
            }
        });
        if (!templates.header().isBlank() || !templates.footer().isBlank()) {
            MinecraftServer.getSchedulerManager().buildTask(this::updateTabList).repeat(TAB_REFRESH).schedule();
        }
    }

    private void updateTabList() {
        var players = MinecraftServer.getConnectionManager().getOnlinePlayers();
        TagResolver counts = TagResolver.resolver(
                Placeholder.unparsed("online", String.valueOf(players.size())),
                Placeholder.unparsed("max", String.valueOf(templates.maxPlayers())),
                Placeholder.unparsed("server", templates.serverName()),
                Placeholder.unparsed("server-id", templates.serverId()));
        for (Player player : players) {
            player.sendPlayerListHeaderAndFooter(MINI_MESSAGE.deserialize(templates.header(), counts),
                    MINI_MESSAGE.deserialize(templates.footer(), counts));
        }
    }

    /** Player names and messages go in as plain text, so nobody can smuggle formatting into chat. */
    private Component render(String template, Player player, TagResolver... extra) {
        return MINI_MESSAGE.deserialize(template, TagResolver.resolver(
                Placeholder.parsed("prefix", permissions.prefixOf(player.getUsername())),
                Placeholder.unparsed("name", player.getUsername()),
                TagResolver.resolver(extra)));
    }

    private static void broadcast(Component message) {
        MinecraftServer.getConnectionManager().getOnlinePlayers().forEach(player -> player.sendMessage(message));
    }
}
