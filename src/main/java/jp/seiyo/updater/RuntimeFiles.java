package jp.seiyo.updater;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class RuntimeFiles {
    static final String AGENT_ROOT = "seiyo.bootstrap.root";

    static Path cache(Path root, boolean mods) {
        return root.resolve(UpdateWorker.HOME).resolve(mods ? "runtime-mods" : "runtime-packs");
    }

    static List<Path> prepare(Path root, boolean mods) throws Exception {
        String extension = mods ? ".jar" : ".zip";
        Path cache = UpdateWorker.directory(cache(root, mods));
        clean(cache, extension);
        List<Path> result = new ArrayList<>();
        try {
            Path folder = UpdateWorker.directory(root.resolve(mods ? "mods" : "resourcepacks"));
            try (var sources = Files.list(folder)) {
                for (Path file : sources.filter(PackedArchive::isPacked).sorted().toList()) {
                    if (!Files.isRegularFile(file, UpdateWorker.NO_LINKS)) continue;
                    PackedArchive.Header header = PackedArchive.header(file);
                    String stored = file.getFileName().toString();
                    String name = mods ? stored.substring(0, stored.length() - PackedArchive.EXTENSION.length()) + extension
                        : header.filename();
                    if (!UpdateWorker.validFilename(name) || !header.filename().endsWith(extension)
                            || (!mods && !stored.equals(name + PackedArchive.EXTENSION))
                            || (mods && (!stored.matches("[a-z][a-z0-9_-]{1,63}\\.syp") || name.equals("seiyo_updater.jar")))) {
                        throw new IOException("Invalid SYP archive name: " + file);
                    }
                    if (!mods && UpdateWorker.exists(folder.resolve(name))) {
                        throw new IOException("Both ZIP and SYP are present: " + name);
                    }
                    Path target = cache.resolve(name);
                    PackedArchive.unpack(file, target);
                    result.add(target);
                }
            }
        } catch (Exception failure) {
            try {
                clean(cache, extension);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                clean(cache, extension);
            } catch (IOException ignored) {
                // Windows can hold JARs open. The next launch cleans the cache under its lock.
            }
        }, "Seiyo archive cleanup"));
        return result;
    }

    private static void clean(Path cache, String extension) throws IOException {
        try (var files = Files.list(cache)) {
            for (Path file : files.toList()) {
                if (file.getFileName().toString().endsWith(extension)
                        && Files.isRegularFile(file, UpdateWorker.NO_LINKS)) Files.delete(file);
            }
        }
    }

    private RuntimeFiles() {}
}
