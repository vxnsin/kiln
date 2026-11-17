package dev.vensin.kiln.server.plugin;

import dev.vensin.kiln.api.Events;
import dev.vensin.kiln.api.Menu;
import dev.vensin.kiln.api.Menus;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.minestom.server.entity.Player;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.inventory.AbstractInventory;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.ItemStack;

final class PluginMenus implements Menus {

    private static final InventoryType[] CHESTS = {
        InventoryType.CHEST_1_ROW, InventoryType.CHEST_2_ROW, InventoryType.CHEST_3_ROW,
        InventoryType.CHEST_4_ROW, InventoryType.CHEST_5_ROW, InventoryType.CHEST_6_ROW
    };

    // Weak, so a menu built per click is forgotten once nobody has it open or keeps a reference.
    private final Map<AbstractInventory, ChestMenu> menus = Collections.synchronizedMap(new WeakHashMap<>());

    PluginMenus(Events events) {
        events.on(InventoryPreClickEvent.class, this::click);
    }

    @Override
    public Menu chest(int rows, Component title) {
        if (rows < 1 || rows > CHESTS.length) {
            throw new IllegalArgumentException("A chest menu has 1 to 6 rows, not " + rows);
        }
        ChestMenu menu = new ChestMenu(new Inventory(CHESTS[rows - 1], title));
        menus.put(menu.inventory, menu);
        return menu;
    }

    /** Any click while one of these menus is open is cancelled, shift clicks from the player's own items too. */
    private void click(InventoryPreClickEvent event) {
        Player player = event.getPlayer();
        ChestMenu open = player.getOpenInventory() == null ? null : menus.get(player.getOpenInventory());
        if (open == null) {
            return;
        }
        event.setCancelled(true);
        if (event.getInventory() == open.inventory) {
            Consumer<Player> action = open.actions.get(event.getSlot());
            if (action != null) {
                action.accept(player);
            }
        }
    }

    void closeAll() {
        List<ChestMenu> all;
        synchronized (menus) {
            all = List.copyOf(menus.values());
        }
        all.forEach(ChestMenu::close);
        menus.clear();
    }

    private static final class ChestMenu implements Menu {

        private final Inventory inventory;
        private final Map<Integer, Consumer<Player>> actions = new ConcurrentHashMap<>();

        private ChestMenu(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Menu item(int slot, ItemStack item) {
            check(slot);
            actions.remove(slot);
            inventory.setItemStack(slot, item);
            return this;
        }

        @Override
        public Menu button(int slot, ItemStack item, Consumer<Player> action) {
            check(slot);
            actions.put(slot, action);
            inventory.setItemStack(slot, item);
            return this;
        }

        @Override
        public Menu fill(ItemStack item) {
            for (int slot = 0; slot < size(); slot++) {
                if (inventory.getItemStack(slot).isAir()) {
                    inventory.setItemStack(slot, item);
                }
            }
            return this;
        }

        @Override
        public Menu clear(int slot) {
            return item(slot, ItemStack.AIR);
        }

        @Override
        public int size() {
            return inventory.getSize();
        }

        @Override
        public void open(Player player) {
            player.openInventory(inventory);
        }

        @Override
        public void close() {
            List.copyOf(inventory.getViewers()).forEach(Player::closeInventory);
        }

        private void check(int slot) {
            if (slot < 0 || slot >= size()) {
                throw new IllegalArgumentException("Slot " + slot + " is outside this menu of " + size() + " slots");
            }
        }
    }
}
