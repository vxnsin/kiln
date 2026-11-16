package dev.vensin.kiln.server.command;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.dialog.Dialog;
import net.minestom.server.network.NetworkBuffer;
import org.junit.jupiter.api.Test;

class MenuTest {

    /** Without a client to look at it, writing the dialog the way it goes over the wire is the closest check. */
    @Test
    void theMenuCanBeSentToAClient() {
        MinecraftServer.init();
        byte[] bytes = NetworkBuffer.makeArray(Dialog.NETWORK_TYPE, KilnCommand.menu(Component.text("status"), false),
                MinecraftServer.process());
        String written = new String(bytes, StandardCharsets.ISO_8859_1);
        assertTrue(written.contains("/kiln plugins"), "buttons run their commands");
        assertTrue(written.contains("Maintenance on"));
    }
}
