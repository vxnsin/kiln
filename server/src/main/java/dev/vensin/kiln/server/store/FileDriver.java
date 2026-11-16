package dev.vensin.kiln.server.store;

import dev.vensin.kiln.api.store.Store;
import java.nio.file.Path;

public final class FileDriver implements StorageDriver {

    private final Path root;

    public FileDriver(Path root) {
        this.root = root;
    }

    @Override
    public Store forPlugin(String plugin) {
        return new FileStore(root.resolve(StorageDriver.safe(plugin)));
    }

    @Override
    public String describe() {
        return "files in " + root;
    }
}
