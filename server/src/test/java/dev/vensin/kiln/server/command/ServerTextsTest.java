package dev.vensin.kiln.server.command;

import static org.junit.jupiter.api.Assertions.assertTrue;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ServerTextsTest {

    @AfterEach
    void backToEnglish() {
        ServerTexts.use("en");
    }

    @Test
    void screensFollowTheServerLanguageAndFallBackToEnglish() {
        ServerTexts.use("de");
        assertTrue(plain(ServerTexts.screen("full")).contains("Der Server ist voll"));
        assertTrue(plain(ServerTexts.screen("kicked", "Spam")).contains("Spam"), "a given reason replaces the advice");

        ServerTexts.use("fr");
        assertTrue(plain(ServerTexts.screen("full")).contains("The server is full"));
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
