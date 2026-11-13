package dev.vensin.kiln.api;

/** Entry point of a plugin, named by {@code main} in its kiln-plugin.yml. Needs a public no-argument constructor. */
public interface Plugin {

    /** Called once at startup, after every plugin this one depends on. Register events and commands here. */
    void enable(Kiln kiln);

    default void disable() {}
}
