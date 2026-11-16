package dev.vensin.kiln.server.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigMappingTest {

    static final class Settings {
        boolean announceFirstVisit = true;
        int rounds = 3;
        String mapName = "arena";
    }

    @Test
    void dashedKeysFillFieldsAndMissingOnesKeepTheirDefault() {
        Gson gson = new GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_DASHES).create();
        Settings settings = gson.fromJson(gson.toJsonTree(Map.of("announce-first-visit", false, "rounds", 5)), Settings.class);
        assertFalse(settings.announceFirstVisit);
        assertEquals(5, settings.rounds);
        assertEquals("arena", settings.mapName);
    }
}
