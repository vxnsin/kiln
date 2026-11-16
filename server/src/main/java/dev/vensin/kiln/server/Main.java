package dev.vensin.kiln.server;

import dev.vensin.kiln.server.console.Terminal;

public final class Main {

    private Main() {}

    public static void main(String[] args) {
        // Before any class that logs or prints is loaded, see Terminal.
        Terminal.prepare();
        if (args.length > 0 && args[0].equals("new")) {
            System.exit(PluginScaffold.run(args));
        }
        Server.run();
    }
}
