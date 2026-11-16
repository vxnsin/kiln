package dev.vensin.kiln.server.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vensin.kiln.server.store.FileStore;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BansTest {

    @TempDir
    Path folder;

    @Test
    void readsDurations() {
        assertEquals(Duration.ofMinutes(30), Bans.parse("30m"));
        assertEquals(Duration.ofHours(12), Bans.parse("12H"));
        assertEquals(Duration.ofDays(14), Bans.parse("2w"));
        assertNull(Bans.parse("griefing"));
        assertNull(Bans.parse("5y"));
    }

    @Test
    void aBanHoldsUntilItEndsAndNamesIgnoreCase() throws Exception {
        Bans bans = new Bans(new FileStore(folder).table("bans", Bans.Ban.class));
        bans.ban("Griefer", null, "broke the spawn", "console");
        bans.ban("Spammer", Duration.ofMillis(1), "", "console");
        Thread.sleep(5);

        assertTrue(bans.active("griefer").isPresent());
        assertFalse(bans.active("spammer").isPresent(), "an ended ban lets the player in");
        assertEquals(1, bans.all().size());
        assertTrue(bans.unban("GRIEFER"));
        assertFalse(bans.active("griefer").isPresent());
    }
}
