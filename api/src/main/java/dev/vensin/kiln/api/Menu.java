package dev.vensin.kiln.api;

import java.util.function.Consumer;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;

/**
 * A chest menu. Every viewer sees the same items, so build one per player for personal content. Changes show up
 * at once for everyone who has it open.
 */
public interface Menu {

    /** An item that does nothing when clicked. Slots count from 0, left to right and top to bottom. */
    Menu item(int slot, ItemStack item);

    /** An item that runs {@code action} for the player who clicked it. */
    Menu button(int slot, ItemStack item, Consumer<Player> action);

    /** Puts {@code item} into every slot that is still empty, for a background. */
    Menu fill(ItemStack item);

    /** Empties the slot and forgets its action. */
    Menu clear(int slot);

    int size();

    void open(Player player);

    /** Closes the menu for everyone who has it open. */
    void close();
}
