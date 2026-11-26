package jp.seiyo.updater;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

final class ServerUpdates {
    private static final String CONFIG = "seiyo-server-updater.json";
    static final String PREUPDATED = "SEIYO_SERVER_PREUPDATED";

    private record Mod(Path path, String id, String version, JsonObject metadata) {}

    static int run(Path instance, List<String> launch) throws Exception {
        Path root = instance.toRealPath();
        Path home = UpdateWorker.directory(root.resolve(UpdateWorker.HOME));
        try (var channel = UpdateWorker.lockChannel(home); var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("別の星陽プロセスがこのサーバーを使用しています。");
            UpdateWorker.applyPending(root, home);
            if (stage(root, home, false)) {
                UpdateWorker.applyPending(root, home);
                System.out.println("サーバー Mod の更新を適用しました。");
            }
            if (launch.isEmpty()) return 0;
            return launch(root, launch);
        }
    }

    static int launch(Path root, List<String> command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile()).inheritIO();
        builder.environment().put(PREUPDATED, root.toString());
        Process server = builder.start();
        Thread shutdown = new Thread(() -> {
            server.destroy();
            try {
                server.waitFor();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "seiyo-server-stop");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            return server.waitFor();
        } finally {
            Runtime.getRuntime().removeShutdownHook(shutdown);
        }
    }

    static boolean stage(Path root, Path home, boolean mandatoryOnly) throws Exception {
        Path configPath = root.resolve("config").resolve(CONFIG);
        if (!Files.exists(configPath)) configPath = configPath.resolveSibling(Catalog.CONFIG_NAME);
        JsonObject settings;
        Catalog.Config config;
        if (Files.exists(configPath)) {
            config = Catalog.Config.load(configPath);
            try (var input = Files.newInputStream(configPath)) {
                settings = Catalog.parse(Catalog.read(input, 16384));
            }
        } else {
            String url = System.getenv("SEIYO_MANIFEST_URL");
            if (url == null || url.isBlank()) {
                throw new IOException("config/" + CONFIG + " または SEIYO_MANIFEST_URL に配布 URL を指定してください。");
            }
            config = new Catalog.Config(Downloads.https(java.net.URI.create(url.trim())), null, null);
            settings = new JsonObject();
        }
        var configured = settings.getAsJsonArray("serverMods");
        Catalog.Release release = Catalog.fetch(config);
        Set<String> selected = new LinkedHashSet<>();
        if (mandatoryOnly || configured == null) {
            for (Catalog.Entry entry : release.entries().values()) {
                if (entry.required() && entry.type().equals("mod") && !entry.clientOnly()) {
                    include(entry.id(), selected, release.entries());
                }
            }
        } else {
            for (var id : configured) {
                if (!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) {
                    throw new IOException("serverMods には文字列のカタログ ID を指定してください。");
                }
                include(id.getAsString(), selected, release.entries());
            }
        }
        Path mods = UpdateWorker.directory(root.resolve("mods"));
        Map<String, List<Mod>> installed = scan(mods);
        Map<String, Mod> desired = new HashMap<>();
        List<UpdateWorker.Change> changes = new ArrayList<>();
        Path job = Files.createDirectory(home.resolve("job-" + UUID.randomUUID()));
        boolean submitted = false;
        try {
            for (String id : selected) {
                Catalog.Entry entry = release.entries().get(id);
                Path target = mods.resolve(entry.storedName());
                String algorithm = entry.metadataOnly() ? "SHA-512" : "SHA-256";
                String expected = entry.metadataOnly() ? entry.sha512() : entry.hash();
                Mod previous = entry.metadataOnly() ? findPath(installed, target) : unique(installed, id);
                if (previous != null && Files.size(previous.path) == entry.size()
                        && UpdateWorker.hash(previous.path, algorithm).equals(expected)) {
                    verifyIdentity(entry, previous);
                    unique(installed, previous.id);
                    addDesired(desired, previous);
                    continue;
                }

                System.out.println("取得: " + entry.name());
                byte[] archive = Catalog.artifact(release.key(), entry);
                Path staged = job.resolve(changes.size() + ".stage");
                Files.write(staged, archive, StandardOpenOption.CREATE_NEW);
                Mod replacement = readMod(staged);
                verifyIdentity(entry, replacement);
                Mod actualPrevious = unique(installed, replacement.id);
                if (previous != null && !previous.id.equals(replacement.id)
                        || UpdateWorker.exists(target) && (actualPrevious == null || !actualPrevious.path.equals(target))) {
                    throw new IOException("既存の Mod と安全に置き換えられません: " + entry.name());
                }
                addDesired(desired, replacement);
                changes.add(new UpdateWorker.Change("mods",
                    actualPrevious == null ? "" : actualPrevious.path.getFileName().toString(),
                    target.getFileName().toString(),
                    actualPrevious == null ? "" : UpdateWorker.hash(actualPrevious.path), Catalog.hex(archive)));
            }
            validateDependencies(installed, desired);
            Catalog.rememberRevision(home, config, release);
            if (changes.isEmpty()) {
                System.out.println("サーバー Mod は最新です。");
                return false;
            }
            UpdateWorker.save(job, changes);
            Path marker = Files.writeString(job.resolve("pending.tmp"), job.getFileName().toString());
            Files.move(marker, home.resolve(UpdateWorker.PENDING), StandardCopyOption.ATOMIC_MOVE);
            submitted = true;
            System.out.println("サーバー Mod の更新を準備しました: " + changes.size() + " 件");
            return true;
        } finally {
            if (!submitted) {
                try (var files = Files.list(job)) {
                    for (Path file : files.toList()) Files.delete(file);
                }
                Files.delete(job);
            }
        }
    }

    private static void include(String id, Set<String> selected, Map<String, Catalog.Entry> entries) throws IOException {
        Catalog.Entry entry = entries.get(id);
        if (entry == null || !entry.type().equals("mod") || entry.clientOnly()) {
            throw new IOException("サーバー用の Mod がカタログにありません: " + id);
        }
        if (selected.add(id)) {
            for (String dependency : entry.dependencies()) include(dependency, selected, entries);
        }
    }

    private static Map<String, List<Mod>> scan(Path mods) throws IOException {
        Map<String, List<Mod>> installed = new HashMap<>();
        try (var paths = Files.list(mods)) {
            for (Path path : paths.toList()) {
                if (!Files.isRegularFile(path, UpdateWorker.NO_LINKS)
                        || !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
                Mod mod;
                try {
                    mod = readMod(path);
                } catch (ZipException ignored) {
                    continue;
                }
                if (mod != null) installed.computeIfAbsent(mod.id, key -> new ArrayList<>()).add(mod);
            }
        }
        return installed;
    }

    private static Mod readMod(Path path) throws IOException {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            var descriptor = zip.getEntry("fabric.mod.json");
            if (descriptor == null) return null;
            try (var stream = zip.getInputStream(descriptor)) {
                var data = Catalog.parse(Catalog.read(stream, Catalog.MAX_JSON));
                return new Mod(path, Catalog.required(data, "id", 64), Catalog.required(data, "version", 100), data);
            }
        }
    }

    private static Mod findPath(Map<String, List<Mod>> installed, Path path) {
        return installed.values().stream().flatMap(List::stream).filter(mod -> mod.path.equals(path)).findFirst().orElse(null);
    }

    private static Mod unique(Map<String, List<Mod>> installed, String id) throws IOException {
        List<Mod> choices = installed.getOrDefault(id, List.of());
        if (choices.size() > 1) throw new IOException("既存 Mod が重複しています: " + id);
        return choices.isEmpty() ? null : choices.get(0);
    }

    private static void verifyIdentity(Catalog.Entry entry, Mod mod) throws IOException {
        if (mod == null || !mod.id.matches("[a-z][a-z0-9_-]{1,63}") || mod.id.equals("seiyo_updater")
                || mod.metadata.has("environment") && mod.metadata.get("environment").getAsString().equals("client")
                || !entry.metadataOnly() && (!entry.id().equals(mod.id) || !entry.version().equals(mod.version))) {
            throw new IOException("専用サーバー用 Fabric Mod として確認できません: " + entry.name());
        }
    }

    private static void addDesired(Map<String, Mod> desired, Mod mod) throws IOException {
        if (desired.putIfAbsent(mod.id, mod) != null) throw new IOException("同じ Mod ID を複数登録しています: " + mod.id);
    }

    private static void validateDependencies(Map<String, List<Mod>> installed, Map<String, Mod> desired) throws Exception {
        for (Mod mod : desired.values()) {
            JsonObject dependencies = mod.metadata.getAsJsonObject("depends");
            if (dependencies == null) continue;
            for (var dependency : dependencies.entrySet()) {
                String id = dependency.getKey();
                String version = switch (id) {
                    case "minecraft" -> Catalog.GAME_VERSION;
                    case "java" -> Integer.toString(Runtime.version().feature());
                    default -> {
                        Mod available = desired.get(id);
                        if (available == null) available = unique(installed, id);
                        yield available == null ? null : available.version;
                    }
                };
                if (version != null && !matches(dependency.getValue(), Version.parse(version))) {
                    throw new IOException(mod.id + " の依存関係 " + id + " がバージョン " + version + " に対応していません。");
                }
            }
        }
    }

    private static boolean matches(JsonElement value, Version version) throws Exception {
        if (!value.isJsonArray()) return VersionPredicate.parse(value.getAsString()).test(version);
        for (JsonElement range : value.getAsJsonArray()) {
            if (VersionPredicate.parse(range.getAsString()).test(version)) return true;
        }
        return false;
    }

    private ServerUpdates() {}
}
