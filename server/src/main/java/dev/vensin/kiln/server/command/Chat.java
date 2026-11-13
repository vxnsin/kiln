package dev.vensin.kiln.server.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.ansi.ANSIComponentSerializer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One look for everything Kiln says: players get the prefix, the console gets the bare line. */
public final class Chat {

    public static final String PREFIX =
            "<gradient:#ff8700:#ffd700><bold>Kiln</bold></gradient> <dark_gray>»</dark_gray> ";
    private static final String INDENT = "  <dark_gray>▪</dark_gray> ";
    private static final Logger LOG = LoggerFactory.getLogger(Chat.class);
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private Chat() {}

    public static void say(CommandSender sender, String miniMessage) {
        send(sender, PREFIX, miniMessage);
    }

    /** A line that belongs under the one before it, e.g. an entry of a list. */
    public static void item(CommandSender sender, String miniMessage) {
        send(sender, INDENT, miniMessage);
    }

    public static void error(CommandSender sender, String miniMessage) {
        say(sender, "<red>" + miniMessage + "</red>");
    }

    /** The text of a disconnect screen: who is talking, what happened, what to do about it. */
    public static Component screen(String headline, String advice) {
        return MINI_MESSAGE.deserialize("<gradient:#ff8700:#ffd700><bold>Kiln</bold></gradient><newline><newline><white>"
                + headline + "</white><newline><gray>" + advice + "</gray>");
    }

    private static void send(CommandSender sender, String lead, String miniMessage) {
        if (sender instanceof Player player) {
            player.sendMessage(MINI_MESSAGE.deserialize(lead + miniMessage));
        } else {
            Component line = MINI_MESSAGE.deserialize((lead.equals(PREFIX) ? "" : lead) + miniMessage);
            LOG.info(ANSIComponentSerializer.ansi().serialize(line));
        }
    }
}
