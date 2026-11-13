package dev.vensin.kiln.server.console;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

public final class ConsoleAppender extends AppenderBase<ILoggingEvent> {

    private final LogLayout layout = new LogLayout();

    @Override
    protected void append(ILoggingEvent event) {
        String text = layout.doLayout(event);
        if (!InteractiveConsole.print(text)) {
            System.out.print(text);
            System.out.flush();
        }
    }
}
