package dev.vensin.kiln.server.world;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import dev.vensin.kiln.server.command.ServerTexts;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntArrayBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.LightingChunk;
import net.minestom.server.instance.anvil.AnvilLoader;

public final class WorldFiles {

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pos NO_SPAWN = new Pos(0.5, 64, 0.5);
    private static final Key OVERWORLD = Key.key("minecraft", "overworld");
    private static final String MODERN_REGION = "dimensions/minecraft/overworld/region";

    private final Path root;
    private volatile Instance home;
    private volatile Pos homeSpawn = NO_SPAWN;

    public WorldFiles(Path root) {
        this.root = root;
    }

    /** Where players go when the instance they are in is closed. */
    public void home(Instance instance, Pos spawn) {
        this.home = instance;
        this.homeSpawn = spawn;
    }

    public Instance home() {
        return home;
    }

    public Pos homeSpawn() {
        return homeSpawn;
    }

    public List<String> available() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> folders = Files.list(root)) {
            return folders.filter(WorldFiles::isWorld)
                    .map(folder -> folder.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public InstanceContainer open(String name) {
        Path folder = folder(name);
        if (!isWorld(folder)) {
            throw new IllegalArgumentException("worlds/" + name + " holds no Minecraft world");
        }
        InstanceContainer instance = MinecraftServer.getInstanceManager()
                .createInstanceContainer(new ReadOnlyLoader(loader(folder)));
        instance.setChunkSupplier(LightingChunk::new);
        return instance;
    }

    /** Reads both level.dat layouts: spawn.pos since Minecraft 1.21.5, SpawnX/Y/Z before that. */
    public Pos spawn(String name) {
        Path level = folder(name).resolve("level.dat");
        if (!Files.exists(level)) {
            return NO_SPAWN;
        }
        try {
            CompoundBinaryTag data = BinaryTagIO.reader().read(level, BinaryTagIO.Compression.GZIP).getCompound("Data");
            CompoundBinaryTag spawn = data.getCompound("spawn");
            if (spawn.get("pos") instanceof IntArrayBinaryTag pos && pos.size() == 3) {
                return new Pos(pos.get(0) + 0.5, pos.get(1), pos.get(2) + 0.5, spawn.getFloat("yaw"), spawn.getFloat("pitch"));
            }
            if (data.keySet().contains("SpawnX")) {
                return new Pos(data.getInt("SpawnX") + 0.5, data.getInt("SpawnY"), data.getInt("SpawnZ") + 0.5,
                        data.getFloat("SpawnAngle"), 0);
            }
        } catch (IOException | RuntimeException unreadable) {
            // fall through to the default
        }
        return NO_SPAWN;
    }

    public void close(Instance instance) {
        if (instance == home) {
            throw new IllegalArgumentException("the default world cannot be closed");
        }
        for (Player player : List.copyOf(instance.getPlayers())) {
            if (home != null) {
                player.setInstance(home, homeSpawn);
            } else {
                player.kick(ServerTexts.screen("world-closed"));
            }
        }
        instance.scheduleNextTick(WorldFiles::release);
    }


    /** Unloading every chunk first closes the region files, so the folder can be replaced while the server runs. */
    static void release(Instance instance) {
        List.copyOf(instance.getChunks()).forEach(instance::unloadChunk);
        MinecraftServer.getInstanceManager().unregisterInstance(instance);
    }
    /** Since 26.1 the overworld lives in dimensions/minecraft/overworld; older saves keep region/ at the top. */
    private static boolean isWorld(Path folder) {
        return Files.isDirectory(folder.resolve(MODERN_REGION)) || Files.isDirectory(folder.resolve("region"));
    }

    @SuppressWarnings("removal")
    private static ChunkLoader loader(Path folder) {
        return Files.isDirectory(folder.resolve(MODERN_REGION)) ? new AnvilLoader(folder, OVERWORLD) : new AnvilLoader(folder);
    }

    private Path folder(String name) {
        if (!SAFE_NAME.matcher(name).matches() || name.startsWith(".")) {
            throw new IllegalArgumentException("world names may only use letters, digits, '.', '-' and '_': " + name);
        }
        return root.resolve(name);
    }
}
