package dev.vensin.kiln.server.command;

import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.ansi.ANSIComponentSerializer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One look for everything the server says: players get the prefix, the console gets the bare line. */
public final class Chat {

    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final String INDENT = "  <dark_gray>▪</dark_gray> ";
    private static final Logger LOG = LoggerFactory.getLogger(Chat.class);
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private static volatile String brand = gradient("Kiln", "#ff8700", "#ffd700");

    private Chat() {}

    /** The name and colors shown in front of messages and on disconnect screens. Bad colors fall back to Kiln's. */
    public static void brand(String name, String from, String to) {
        String start = COLOR.matcher(from).matches() ? from : "#ff8700";
        String end = COLOR.matcher(to).matches() ? to : "#ffd700";
        brand = gradient(name.isBlank() ? "Kiln" : name, start, end);
    }

    public static void say(CommandSender sender, String miniMessage) {
        if (sender instanceof Player player) {
            player.sendMessage(MINI_MESSAGE.deserialize(brand + " <dark_gray>»</dark_gray> " + miniMessage));
        } else {
            LOG.info(ANSIComponentSerializer.ansi().serialize(MINI_MESSAGE.deserialize(miniMessage)));
        }
    }

    /** A line that belongs under the one before it, e.g. an entry of a list. */
    public static void item(CommandSender sender, String miniMessage) {
        Component line = MINI_MESSAGE.deserialize(INDENT + miniMessage);
        if (sender instanceof Player player) {
            player.sendMessage(line);
        } else {
            LOG.info(ANSIComponentSerializer.ansi().serialize(line));
        }
    }

    public static void error(CommandSender sender, String miniMessage) {
        say(sender, "<red>" + miniMessage + "</red>");
    }

    /** The server name in its colors, for titles. */
    public static Component title() {
        return MINI_MESSAGE.deserialize(brand);
    }

    /** The text of a disconnect screen: who is talking, what happened, what to do about it. */
    public static Component screen(String headline, String advice) {
        return MINI_MESSAGE.deserialize(brand + "<newline><newline><white>" + headline + "</white><newline><gray>"
                + advice + "</gray>");
    }

    /** The name is inserted as plain text, so a name like "<red>" cannot break the markup around it. */
    private static String gradient(String name, String from, String to) {
        String safe = MINI_MESSAGE.escapeTags(name);
        return "<gradient:" + from + ":" + to + "><bold>" + safe + "</bold></gradient>";
    }
}
