package dev.vensin.kiln.server.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PermissionsTest {

    @TempDir
    Path folder;

    private Permissions permissions() throws Exception {
        Files.writeString(folder.resolve("permissions.yml"), """
                groups:
                  default:
                    permissions: [lobby.join]
                  builder:
                    inherits: [default]
                    prefix: "<green>Builder</green> "
                    weight: 5
                    permissions: [build.*]
                  admin:
                    inherits: [builder, admin]
                    prefix: "<red>Admin</red> "
                    weight: 20
                    permissions: [kiln.reload]
                players:
                  Alex: [admin]
                """);
        return new Permissions(folder.resolve("permissions.yml"), new Operators(folder.resolve("ops.txt")));
    }

    @Test
    void groupsInheritAndWildcardsCoverChildren() throws Exception {
        Permissions permissions = permissions();
        assertTrue(permissions.groupsOf("alex").contains("admin"), "player names are case-insensitive");
        assertTrue(permissions.groupsOf("someone").contains(Permissions.DEFAULT_GROUP));
        assertTrue(permissions.groupNames().containsAll(Set.of("default", "builder", "admin")));
    }

    @Test
    void givingAGroupIsSavedAndSurvivesAReload() throws Exception {
        Permissions permissions = permissions();
        assertTrue(permissions.give("Sam", "builder"));
        assertFalse(permissions.give("Sam", "builder"));
        permissions.reload();
        assertTrue(permissions.groupsOf("sam").contains("builder"));
        assertTrue(Files.readString(folder.resolve("permissions.yml")).startsWith("# Groups"));
        assertTrue(permissions.take("Sam", "builder"));
        assertFalse(permissions.groupsOf("sam").contains("builder"));
    }

    @Test
    void wildcardsCoverEverythingBelowThem() throws Exception {
        Set<String> admin = permissions().grantedTo("alex");
        assertTrue(Permissions.grants(admin, "lobby.join"), "inherited from default through builder");
        assertTrue(Permissions.grants(admin, "build.place.fast"));
        assertTrue(Permissions.grants(admin, "kiln.reload"));
        assertFalse(Permissions.grants(admin, "kiln.stop"));
        assertFalse(Permissions.grants(admin, "buildings"));
        assertTrue(Permissions.grants(Set.of("*"), "anything.at.all"));
    }

    @Test
    void theHeaviestGroupWithAPrefixWins() throws Exception {
        Permissions permissions = permissions();
        permissions.give("Alex", "builder");
        assertEquals("<red>Admin</red> ", permissions.prefixOf("alex"));
        assertEquals("", permissions.prefixOf("someone"));
        permissions.reload();
        assertEquals("<red>Admin</red> ", permissions.prefixOf("alex"), "prefix and weight survive saving");
    }
}
