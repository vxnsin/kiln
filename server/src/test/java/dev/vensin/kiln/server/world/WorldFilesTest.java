package dev.vensin.kiln.server.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntArrayBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.anvil.AnvilLoader;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldFilesTest {

    @TempDir
    static Path root;

    @BeforeAll
    static void saveWorld() throws Exception {
        MinecraftServer.init();
        InstanceContainer source = MinecraftServer.getInstanceManager()
                .createInstanceContainer(new AnvilLoader(root.resolve("arena"), Key.key("minecraft", "overworld")));
        source.loadChunk(0, 0).join();
        source.setBlock(3, 70, 4, Block.GOLD_BLOCK);
        source.saveChunksToStorage().join();
        WorldFiles.release(source);

        CompoundBinaryTag spawn = CompoundBinaryTag.builder().put("pos", IntArrayBinaryTag.intArrayBinaryTag(3, 71, 4))
                .putFloat("yaw", 90f).build();
        BinaryTagIO.writer().write(CompoundBinaryTag.builder().put("Data", CompoundBinaryTag.builder()
                .put("spawn", spawn).build()).build(), root.resolve("arena/level.dat"), BinaryTagIO.Compression.GZIP);
        Files.createDirectories(root.resolve("not-a-world"));
    }

    @Test
    void listsOnlyFoldersThatHoldAWorld() {
        assertEquals(List.of("arena"), new WorldFiles(root).available());
    }

    @Test
    void everyCopyStartsFromTheSavedState() {
        WorldFiles worlds = new WorldFiles(root);
        InstanceContainer first = worlds.open("arena");
        first.loadChunk(0, 0).join();
        assertTrue(first.getBlock(3, 70, 4).compare(Block.GOLD_BLOCK));
        first.setBlock(3, 70, 4, Block.AIR);
        first.saveChunksToStorage().join();

        InstanceContainer second = worlds.open("arena");
        second.loadChunk(0, 0).join();
        assertTrue(second.getBlock(3, 70, 4).compare(Block.GOLD_BLOCK), "changes must never reach the files");
        WorldFiles.release(first);
        WorldFiles.release(second);
    }

    @Test
    void readsTheSpawnFromLevelDat() {
        assertEquals(new Pos(3.5, 71, 4.5, 90f, 0f), new WorldFiles(root).spawn("arena"));
    }
}
