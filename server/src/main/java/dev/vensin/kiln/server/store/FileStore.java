package dev.vensin.kiln.server.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.vensin.kiln.api.store.Store;
import dev.vensin.kiln.api.store.Table;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** One JSON file per key: readable, diffable and easy to back up. */
public final class FileStore implements Store {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final String SUFFIX = ".json";

    private final Path root;

    public FileStore(Path root) {
        this.root = root;
    }

    @Override
    public <T> Table<T> table(String name, Class<T> type) {
        return new FileTable<>(root.resolve(safe(name)), type);
    }

    private static String safe(String name) {
        if (!SAFE_NAME.matcher(name).matches() || name.startsWith(".")) {
            throw new IllegalArgumentException("names may only use letters, digits, '.', '-' and '_': " + name);
        }
        return name;
    }

    private record FileTable<T>(Path directory, Class<T> type) implements Table<T> {

        @Override
        public Optional<T> get(String key) {
            Path file = directory.resolve(safe(key) + SUFFIX);
            if (!Files.exists(file)) {
                return Optional.empty();
            }
            try {
                return Optional.ofNullable(GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), type));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** Written to a temporary file first, so a crash mid-write never leaves half a value behind. */
        @Override
        public void put(String key, T value) {
            Path file = directory.resolve(safe(key) + SUFFIX);
            String name = safe(key);
            try {
                Files.createDirectories(directory);
                // Each write gets its own temporary file, so two saves of the same key cannot mix their bytes.
                Path temporary = Files.createTempFile(directory, name + "-", ".tmp");
                Files.writeString(temporary, GSON.toJson(value, type), StandardCharsets.UTF_8);
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public boolean remove(String key) {
            try {
                return Files.deleteIfExists(directory.resolve(safe(key) + SUFFIX));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Set<String> keys() {
            if (!Files.isDirectory(directory)) {
                return Set.of();
            }
            try (Stream<Path> files = Files.list(directory)) {
                return files.map(file -> file.getFileName().toString())
                        .filter(name -> name.endsWith(SUFFIX))
                        .map(name -> name.substring(0, name.length() - SUFFIX.length()))
                        .collect(Collectors.toUnmodifiableSet());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
