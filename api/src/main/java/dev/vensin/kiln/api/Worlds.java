package dev.vensin.kiln.api;

import java.util.List;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceContainer;

/** Minecraft worlds kept as folders in worlds/, for example saved from singleplayer or built on a Paper server. */
public interface Worlds {

    /** Folder names in worlds/ that hold a Minecraft world. */
    List<String> available();

    /**
     * A fresh copy of worlds/&lt;name&gt;. It reads from disk and never writes back, so every call starts from the
     * same state: open one per round and close it when the round is over.
     */
    InstanceContainer open(String name);

    /** The spawn point saved in the world's level.dat, or the middle of the map at y 64 if there is none. */
    Pos spawn(String name);

    /** Sends everyone inside to the default world and drops the instance. */
    void close(Instance instance);
}
