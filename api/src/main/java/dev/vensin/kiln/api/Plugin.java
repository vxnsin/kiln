package dev.vensin.kiln.api;

/** Entry point of a plugin, named by {@code main} in its kiln-plugin.yml. Needs a public no-argument constructor. */
public interface Plugin {

    /** Called when the plugin is loaded, after every plugin it depends on. Register events and commands here. */
    void enable(Kiln kiln);

    /** Called before the plugin is unloaded. Stop your own tasks and threads here; Kiln removes the rest. */
    default void disable() {}
}
