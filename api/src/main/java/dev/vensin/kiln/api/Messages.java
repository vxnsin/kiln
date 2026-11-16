package dev.vensin.kiln.api;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minestom.server.command.CommandSender;

/**
 * Texts from lang/&lt;language&gt;.yml in the plugin jar, written in MiniMessage. A player gets the file matching
 * the language of their Minecraft client, then the server's default language, then English, then the key itself.
 * A file with the same name in plugins/&lt;name&gt;/lang/ replaces the one in the jar, so texts can be edited.
 */
public interface Messages {

    Component get(CommandSender sender, String key, TagResolver... placeholders);

    void send(CommandSender sender, String key, TagResolver... placeholders);
}
