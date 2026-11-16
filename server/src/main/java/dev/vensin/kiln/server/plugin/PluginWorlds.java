package dev.vensin.kiln.server.plugin;

import dev.vensin.kiln.api.Worlds;
import dev.vensin.kiln.server.world.WorldFiles;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceContainer;

final class PluginWorlds implements Worlds {

    private final WorldFiles files;
    private final Set<Instance> opened = ConcurrentHashMap.newKeySet();

    PluginWorlds(WorldFiles files) {
        this.files = files;
    }

    @Override
    public List<String> available() {
        return files.available();
    }

    @Override
    public InstanceContainer open(String name) {
        InstanceContainer instance = files.open(name);
        opened.add(instance);
        return instance;
    }

    @Override
    public Pos spawn(String name) {
        return files.spawn(name);
    }

    @Override
    public void close(Instance instance) {
        opened.remove(instance);
        files.close(instance);
    }

    void closeAll() {
        for (Instance instance : List.copyOf(opened)) {
            if (MinecraftServer.getInstanceManager().getInstances().contains(instance)) {
                files.close(instance);
            }
        }
        opened.clear();
    }
}
