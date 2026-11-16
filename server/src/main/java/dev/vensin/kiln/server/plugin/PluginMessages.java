package dev.vensin.kiln.server.plugin;

import dev.vensin.kiln.api.Messages;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minestom.server.command.CommandSender;
import net.minestom.server.entity.Player;
import org.slf4j.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

final class PluginMessages implements Messages {

    private static final String FALLBACK = "en";
    private static final int MISSING_LIMIT = 64;

    private final PluginClassLoader loader;
    private final Path overrides;
    private final String serverLanguage;
    private final Logger logger;
    private final Map<String, Map<String, String>> languages = new ConcurrentHashMap<>();
    /** Codes known to have no file, capped so a client sending made-up codes cannot grow it. */
    private final Set<String> missing = ConcurrentHashMap.newKeySet();

    PluginMessages(PluginClassLoader loader, Path overrides, String serverLanguage, Logger logger) {
        this.loader = loader;
        this.overrides = overrides;
        this.serverLanguage = serverLanguage;
        this.logger = logger;
    }

    @Override
    public Component get(CommandSender sender, String key, TagResolver... placeholders) {
        return MiniMessage.miniMessage().deserialize(text(languageOf(sender), key), placeholders);
    }

    @Override
    public void send(CommandSender sender, String key, TagResolver... placeholders) {
        sender.sendMessage(get(sender, key, placeholders));
    }

    private String languageOf(CommandSender sender) {
        if (sender instanceof Player player && player.getLocale() != null) {
            return player.getLocale().getLanguage().toLowerCase(Locale.ROOT);
        }
        return serverLanguage;
    }

    private String text(String language, String key) {
        for (String candidate : new String[] {language, serverLanguage, FALLBACK}) {
            String text = load(candidate).get(key);
            if (text != null) {
                return text;
            }
        }
        return key;
    }

    /**
     * Each language file is read once; nested sections become dotted keys like menu.title. Languages without a
     * file are remembered as missing only up to a limit, since the client chooses the code.
     */
    private Map<String, String> load(String language) {
        Map<String, String> known = languages.get(language);
        if (known != null) {
            return known;
        }
        if (missing.contains(language)) {
            return Map.of();
        }
        Map<String, String> read = read(language);
        if (!read.isEmpty() || language.equals(serverLanguage) || language.equals(FALLBACK)) {
            languages.put(language, read);
        } else if (missing.size() < MISSING_LIMIT) {
            missing.add(language);
        }
        return read;
    }

    private Map<String, String> read(String language) {
        String name = "lang/" + language + ".yml";
        Map<String, String> flat = new HashMap<>();
        try (InputStream in = open(name)) {
            if (in != null) {
                Object yaml = new Yaml(new SafeConstructor(new LoaderOptions()))
                        .load(new InputStreamReader(in, StandardCharsets.UTF_8));
                flatten("", yaml, flat);
            }
        } catch (IOException | RuntimeException e) {
            logger.warn("{} could not be read: {}", name, e.getMessage());
        }
        return flat;
    }

    private InputStream open(String name) throws IOException {
        Path override = overrides.resolve(name);
        if (Files.exists(override)) {
            return Files.newInputStream(override);
        }
        URL bundled = loader.findResource(name);
        if (bundled == null) {
            return null;
        }
        URLConnection connection = bundled.openConnection();
        connection.setUseCaches(false);
        return connection.getInputStream();
    }

    private static void flatten(String prefix, Object value, Map<String, String> into) {
        if (value instanceof Map<?, ?> section) {
            section.forEach((key, child) -> flatten(prefix + key + ".", child, into));
        } else if (value != null && !prefix.isEmpty()) {
            into.put(prefix.substring(0, prefix.length() - 1), String.valueOf(value));
        }
    }
}
