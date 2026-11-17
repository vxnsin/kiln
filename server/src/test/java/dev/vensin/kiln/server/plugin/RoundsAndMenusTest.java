package dev.vensin.kiln.server.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vensin.kiln.api.Menu;
import dev.vensin.kiln.api.Round;
import dev.vensin.kiln.server.round.RoundBoard;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.event.EventNode;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RoundsAndMenusTest {

    @BeforeAll
    static void server() {
        MinecraftServer.init();
    }

    @Test
    void roundRunsOnceAndEndsOnce() {
        RoundBoard board = new RoundBoard();
        AtomicInteger stops = new AtomicInteger();
        board.whenEnded(round -> stops.incrementAndGet());
        PluginRounds rounds = new PluginRounds("game", new PluginTasks(new PluginTiming()), board);
        Instance world = MinecraftServer.getInstanceManager().createInstanceContainer();
        AtomicInteger started = new AtomicInteger();
        AtomicInteger ended = new AtomicInteger();

        Round round = rounds.create(world).onStart(players -> started.incrementAndGet())
                .onEnd(players -> ended.incrementAndGet());
        assertEquals(Round.State.WAITING, round.state());
        assertEquals("waiting", board.list().getFirst().stateName());

        round.start();
        round.start();
        assertEquals(Round.State.RUNNING, round.state());
        assertEquals(1, started.get());

        round.end();
        round.end();
        assertEquals(Round.State.ENDED, round.state());
        assertEquals(1, ended.get());
        assertEquals(1, stops.get(), "stop-after-round hears about each end once");
        assertTrue(board.list().isEmpty());
    }

    @Test
    void unloadingDropsRoundsWithoutEndingThem() {
        RoundBoard board = new RoundBoard();
        AtomicInteger stops = new AtomicInteger();
        board.whenEnded(round -> stops.incrementAndGet());
        PluginRounds rounds = new PluginRounds("game", new PluginTasks(new PluginTiming()), board);
        rounds.create(MinecraftServer.getInstanceManager().createInstanceContainer());

        rounds.closeAll();
        assertTrue(board.list().isEmpty());
        assertEquals(0, stops.get());
    }

    @Test
    void hologramsInAnEmptyWorldCanBeChangedAndRemoved() {
        PluginHolograms holograms = new PluginHolograms(new PluginEvents(EventNode.all("hologram-test"), new PluginTiming()));
        Instance world = MinecraftServer.getInstanceManager().createInstanceContainer();

        var hologram = holograms.create(world, new net.minestom.server.coordinate.Pos(0, 64, 0), Component.text("Hi"));
        hologram.text(player -> Component.text("Hi " + player.getUsername()));
        hologram.teleport(new net.minestom.server.coordinate.Pos(1, 65, 1));
        holograms.removeAll();
        assertTrue(world.getEntities().isEmpty());
    }

    @Test
    void menuChecksRowsAndSlots() {
        PluginMenus menus = new PluginMenus(new PluginEvents(EventNode.all("menu-test"), new PluginTiming()));
        assertThrows(IllegalArgumentException.class, () -> menus.chest(7, Component.text("too big")));

        Menu menu = menus.chest(3, Component.text("Shop"));
        assertEquals(27, menu.size());
        assertThrows(IllegalArgumentException.class, () -> menu.item(27, ItemStack.of(Material.STONE)));

        menu.button(13, ItemStack.of(Material.DIAMOND), player -> {}).fill(ItemStack.of(Material.GRAY_STAINED_GLASS_PANE));
        menu.clear(0);
        menus.closeAll();
    }
}
