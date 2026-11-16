package dev.vensin.kiln.server.console;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.LayoutBase;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/** The same lines as the console, with date and level and without colors, for logs/latest.log. */
public final class FileLayout extends LayoutBase<ILoggingEvent> {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final Pattern ESCAPES = Pattern.compile("\u001b" + Pattern.quote("[") + "[0-9;]*m|\u001b]8;;[^\u0007]*\u0007");

    @Override
    public String doLayout(ILoggingEvent event) {
        String logger = event.getLoggerName();
        StringBuilder line = new StringBuilder(TIME.format(Instant.ofEpochMilli(event.getTimeStamp())))
                .append(' ').append(String.format("%-5s", event.getLevel()))
                .append(" [").append(logger.substring(logger.lastIndexOf('.') + 1)).append("] ")
                .append(ESCAPES.matcher(event.getFormattedMessage()).replaceAll(""))
                .append(System.lineSeparator());
        if (event.getThrowableProxy() != null) {
            line.append(ThrowableProxyUtil.asString(event.getThrowableProxy())).append(System.lineSeparator());
        }
        return line.toString();
    }
}
