package dev.vensin.kiln.example;

import dev.vensin.kiln.api.Kiln;
import dev.vensin.kiln.api.Plugin;
import dev.vensin.kiln.api.store.PlayerData;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.minestom.server.command.builder.Command;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerSpawnEvent;

public final class ExamplePlugin implements Plugin {

    /** Saved per player as JSON; a plain class with fields is all it takes. */
    public static final class Visits {
        int count;
    }

    @Override
    public void enable(Kiln kiln) {
        String greeting = String.valueOf(kiln.config().getOrDefault("greeting", "<gold>Welcome, <name>!</gold>"));
        PlayerData<Visits> visits = kiln.playerData("visits", Visits.class, Visits::new);

        kiln.events().addListener(PlayerSpawnEvent.class, event -> {
            if (!event.isFirstSpawn()) {
                return;
            }
            Player player = event.getPlayer();
            Visits own = visits.of(player);
            own.count++;
            player.sendMessage(MiniMessage.miniMessage().deserialize(greeting,
                    Placeholder.unparsed("name", player.getUsername()),
                    Placeholder.unparsed("visits", String.valueOf(own.count))));
        });

        Command command = new Command("visits");
        command.setDefaultExecutor((sender, context) -> {
            if (sender instanceof Player player) {
                player.sendMessage("Visits: " + visits.of(player).count);
            }
        });
        kiln.commands(command);

        kiln.logger().info("Greeting players with: {}", greeting);
    }
}
