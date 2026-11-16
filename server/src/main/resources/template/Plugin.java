package {{package}};

import dev.vensin.kiln.api.Kiln;
import dev.vensin.kiln.api.Plugin;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.minestom.server.event.player.PlayerSpawnEvent;

public final class {{class}} implements Plugin {

    @Override
    public void enable(Kiln kiln) {
        kiln.events().on(PlayerSpawnEvent.class, event -> {
            if (event.isFirstSpawn()) {
                kiln.messages().send(event.getPlayer(), "hello",
                        Placeholder.unparsed("name", event.getPlayer().getUsername()));
            }
        });
        kiln.logger().info("{{name}} is running");
    }
}
