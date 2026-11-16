package dev.vensin.kiln.server.chat;

import dev.vensin.kiln.server.Settings;
import dev.vensin.kiln.server.command.Bans;
import dev.vensin.kiln.server.command.Chat;
import dev.vensin.kiln.server.command.Permissions;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final long MUTE_CACHE_MILLIS = 10_000;

    private volatile Templates templates;
    private final Permissions permissions;
    private final Bans mutes;
    private final Map<String, CachedMute> muteCache = new ConcurrentHashMap<>();

    private record CachedMute(Optional<Bans.Ban> mute, long at) {}

    public static Templates from(Settings settings) {
        return new Templates(settings.chatFormat(), settings.joinMessage(), settings.leaveMessage(), settings.tabHeader(),
                settings.tabFooter(), settings.name(), settings.serverId(), settings.maxPlayers());
    }

    public ChatStyle(Templates templates, Permissions permissions, Bans mutes) {
        this.mutes = mutes;
        this.templates = templates;
        this.permissions = permissions;
    }

    public void register(GlobalEventHandler events) {
        events.addListener(PlayerChatEvent.class, event -> {
            Optional<Bans.Ban> mute = muted(event.getPlayer().getUsername());
            if (mute.isPresent()) {
                event.setCancelled(true);
                String reason = mute.get().reason().isBlank() ? "" : " <gray>· "
                        + MINI_MESSAGE.escapeTags(mute.get().reason()) + "</gray>";
                Chat.say(event.getPlayer(), "<red>You are muted " + Bans.describe(mute.get()) + ".</red>" + reason);
            }
        });
        events.addListener(PlayerChatEvent.class, event -> {
            if (!event.isCancelled() && !templates.chat().isBlank()) {
                event.setFormattedMessage(render(templates.chat(), event.getPlayer(),
                        Placeholder.unparsed("message", event.getRawMessage())));
            }
        });
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
        MinecraftServer.getSchedulerManager().buildTask(this::updateTabList).repeat(TAB_REFRESH).schedule();
    }


    /** Looked up at most every few seconds per player, since chat is busy and storage may be far away. */
    private Optional<Bans.Ban> muted(String name) {
        long now = System.currentTimeMillis();
        CachedMute cached = muteCache.get(name);
        if (cached == null || now - cached.at() > MUTE_CACHE_MILLIS) {
            Optional<Bans.Ban> mute;
            try {
                mute = mutes.active(name);
            } catch (RuntimeException storageDown) {
                mute = cached == null ? Optional.empty() : cached.mute();
            }
            cached = new CachedMute(mute, now);
            muteCache.put(name, cached);
        }
        return cached.mute();
    }

    /** After /kiln mute or unmute, so the change shows on this server right away. */
    public void forget(String name) {
        muteCache.remove(name);
    }
    /** New templates from a reloaded kiln.yml; they apply from the next message on. */
    public void update(Templates templates) {
        this.templates = templates;
    }

    private void updateTabList() {
        Templates current = templates;
        if (current.header().isBlank() && current.footer().isBlank()) {
            return;
        }
        var players = MinecraftServer.getConnectionManager().getOnlinePlayers();
        TagResolver counts = TagResolver.resolver(
                Placeholder.unparsed("online", String.valueOf(players.size())),
                Placeholder.unparsed("max", String.valueOf(current.maxPlayers())),
                Placeholder.unparsed("server", current.serverName()),
                Placeholder.unparsed("server-id", current.serverId()));
        for (Player player : players) {
            player.sendPlayerListHeaderAndFooter(MINI_MESSAGE.deserialize(current.header(), counts),
                    MINI_MESSAGE.deserialize(current.footer(), counts));
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
