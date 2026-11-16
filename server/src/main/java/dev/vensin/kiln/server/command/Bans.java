package dev.vensin.kiln.server.command;

import dev.vensin.kiln.api.store.Table;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/** Bans by name, kept in Kiln's own storage table, so servers sharing a Redis share their bans too. */
public final class Bans {

    /** Stored as JSON; {@code until} is 0 for a ban without end. */
    public record Ban(String name, String reason, String by, long at, long until) {

        boolean expired(long now) {
            return until != 0 && until <= now;
        }
    }

    private static final Pattern DURATION = Pattern.compile("([0-9]+)([mhdw])");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)
            .withZone(ZoneId.systemDefault());

    private final Table<Ban> table;

    public Bans(Table<Ban> table) {
        this.table = table;
    }

    /** The ban that keeps this player out right now; an expired one is removed on the way. */
    public Optional<Ban> active(String name) {
        String key = key(name);
        Optional<Ban> ban = table.get(key);
        if (ban.isPresent() && ban.get().expired(System.currentTimeMillis())) {
            table.remove(key);
            return Optional.empty();
        }
        return ban;
    }

    public Ban ban(String name, Duration length, String reason, String by) {
        long now = System.currentTimeMillis();
        Ban ban = new Ban(name, reason, by, now, length == null ? 0 : now + length.toMillis());
        table.put(key(name), ban);
        return ban;
    }

    public boolean unban(String name) {
        return table.remove(key(name));
    }

    public List<Ban> all() {
        long now = System.currentTimeMillis();
        return table.keys().stream().map(table::get).flatMap(Optional::stream)
                .filter(ban -> !ban.expired(now)).sorted(Comparator.comparingLong(Ban::at).reversed()).toList();
    }

    public static Component screen(Ban ban) {
        String reason = ban.reason().isBlank() ? ServerTexts.advice("banned") : MiniMessage.miniMessage().escapeTags(ban.reason());
        String when = ban.until() == 0 ? ServerTexts.line("ban-forever")
                : ServerTexts.line("ban-until").replace("<date>", DATE.format(Instant.ofEpochMilli(ban.until())));
        return ServerTexts.screen("banned", reason + "<newline>" + when);
    }

    /** 30m, 12h, 7d or 2w; null when the text is not a duration. */
    public static Duration parse(String text) {
        Matcher match = DURATION.matcher(text.toLowerCase(Locale.ROOT));
        if (!match.matches()) {
            return null;
        }
        long amount = Long.parseLong(match.group(1));
        return switch (match.group(2)) {
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            case "d" -> Duration.ofDays(amount);
            default -> Duration.ofDays(amount * 7);
        };
    }

    public static String describe(Ban ban) {
        return ban.until() == 0 ? "for good" : "until " + DATE.format(Instant.ofEpochMilli(ban.until()));
    }

    /** Minecraft names fit the storage rules; anything else, possible in offline mode, is stored hex-encoded. */
    private static String key(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.matches("[a-z0-9_]{1,16}")) {
            return lower;
        }
        return "x-" + HexFormat.of().formatHex(lower.getBytes(StandardCharsets.UTF_8));
    }
}
