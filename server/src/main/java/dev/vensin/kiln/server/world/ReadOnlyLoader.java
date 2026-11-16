package dev.vensin.kiln.server.world;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.Instance;

/** Reads chunks from the world folder and drops every write, so the files on disk stay a clean template. */
final class ReadOnlyLoader implements ChunkLoader {

    private final ChunkLoader source;

    ReadOnlyLoader(ChunkLoader source) {
        this.source = source;
    }

    @Override
    public void loadInstance(Instance instance) {
        source.loadInstance(instance);
    }

    @Override
    public Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
        return source.loadChunk(instance, chunkX, chunkZ);
    }

    @Override
    public boolean supportsParallelLoading() {
        return source.supportsParallelLoading();
    }

    /** Lets the source close a region file once none of its chunks are loaded. */
    @Override
    public void unloadChunk(Chunk chunk) {
        source.unloadChunk(chunk);
    }

    @Override
    public void saveChunk(Chunk chunk) {}

    @Override
    public void saveInstance(Instance instance) {}
}
