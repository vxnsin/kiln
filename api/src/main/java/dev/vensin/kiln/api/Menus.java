package dev.vensin.kiln.api;

import net.kyori.adventure.text.Component;

/** Chest menus whose items cannot be taken out. Open ones are closed when the plugin is unloaded or swapped. */
public interface Menus {

    /** An empty chest menu with 1 to 6 rows of nine slots. */
    Menu chest(int rows, Component title);
}
