package dev.vensin.kiln.server;

import dev.vensin.kiln.server.console.Terminal;

public final class Main {

    private Main() {}

    public static void main(String[] args) {
        // Before any class that logs or prints is loaded, see Terminal.
        Terminal.prepare();
        Server.run();
    }
}
