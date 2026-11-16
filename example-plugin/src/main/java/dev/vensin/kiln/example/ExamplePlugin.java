package dev.vensin.kiln.example;

import dev.vensin.kiln.api.Kiln;
import dev.vensin.kiln.api.Plugin;
import dev.vensin.kiln.api.store.PlayerData;
import dev.vensin.kiln.api.store.Table;
import java.util.Comparator;
import java.util.Optional;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerSpawnEvent;

public final class ExamplePlugin implements Plugin {

    /** Saved per player as JSON; a plain class with fields is all it takes. */
    public static final class Visits {
        int count;
    }

    /** One row of the leaderboard, keyed by player name. */
    public record Score(String name, int visits) {}

    @Override
    public void enable(Kiln kiln) {
        boolean announce = !Boolean.FALSE.equals(kiln.config().get("announce-first-visit"));
        PlayerData<Visits> visits = kiln.playerData("visits", Visits.class, Visits::new);
        Table<Score> scores = kiln.store().table("scores", Score.class);

        kiln.events().on(PlayerSpawnEvent.class, event -> {
            if (!event.isFirstSpawn()) {
                return;
            }
            Player player = event.getPlayer();
            Visits own = visits.of(player);
            own.count++;
            scores.put(player.getUsername().toLowerCase(), new Score(player.getUsername(), own.count));
            if (own.count == 1 && announce) {
                MinecraftServer.getConnectionManager().getOnlinePlayers().forEach(online -> kiln.messages()
                        .send(online, "first-visit", Placeholder.unparsed("name", player.getUsername())));
            } else {
                kiln.messages().send(player, "greeting", Placeholder.unparsed("name", player.getUsername()),
                        Placeholder.unparsed("visits", String.valueOf(own.count)));
            }
        });

        Command command = new Command("visits");
        command.setDefaultExecutor((sender, context) -> {
            if (sender instanceof Player player) {
                kiln.messages().send(player, "visits", Placeholder.unparsed("visits", String.valueOf(visits.of(player).count)));
            }
        });
        Command top = new Command("top");
        top.setDefaultExecutor((sender, context) -> {
            kiln.messages().send(sender, "top");
            scores.keys().stream().map(scores::get).flatMap(Optional::stream)
                    .sorted(Comparator.comparingInt(Score::visits).reversed()).limit(5)
                    .forEach(score -> kiln.messages().send(sender, "top-line", Placeholder.unparsed("name", score.name()),
                            Placeholder.unparsed("visits", String.valueOf(score.visits()))));
        });
        command.addSubcommand(top);
        kiln.commands(command);
    }
}
