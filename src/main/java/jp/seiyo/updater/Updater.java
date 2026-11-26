package jp.seiyo.updater;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class Updater {
    private static final Logger LOGGER = LoggerFactory.getLogger("SeiyoUpdater");
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "SeiyoUpdater");
        thread.setDaemon(true);
        return thread;
    });
    private static CompletableFuture<Snapshot> current;

    record Installed(Path path, String version, String archiveHash, String modId, Path archive) {}
    record Row(Catalog.Entry entry, Installed installed, String installedHash, boolean matched, String problem) {}
    record Snapshot(Path root, byte[] key, long revision, Map<String, Row> rows) {}
    record Plan(Set<String> included, Set<String> locked, List<Row> changes) {}

    static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(Catalog.CONFIG_NAME);
    }

    static synchronized CompletableFuture<Snapshot> configure(String value) {
        current = CompletableFuture.supplyAsync(() -> {
            Path temporary = null;
            try {
                var manifest = Downloads.https(java.net.URI.create(value.trim()));
                var config = new Catalog.Config(manifest, null, null);
                Path destination = configPath();
                Files.createDirectories(destination.getParent());
                temporary = Files.createTempFile(destination.getParent(), "seiyo-updater-", ".tmp");
                JsonObject json = new JsonObject();
                json.addProperty("manifestUrl", manifest.toASCIIString());
                Files.writeString(temporary, json.toString());
                Snapshot snapshot = inspect(config, FabricLoader.getInstance().getGameDir().toRealPath());
                Files.move(temporary, destination);
                return snapshot;
            } catch (Exception failure) {
                throw new CompletionException(failure);
            } finally {
                if (temporary != null) {
                    try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
                }
            }
        }, IO);
        return current;
    }

    static synchronized CompletableFuture<Snapshot> check(boolean refresh) {
        if (current == null || (refresh && current.isDone())) {
            current = CompletableFuture.supplyAsync(() -> {
                try {
                    if (!Files.isRegularFile(configPath())) {
                        throw new IOException("配布元から受け取った設定ファイルを config に入れてください。");
                    }
                    return inspect(Catalog.Config.load(configPath()),
                        FabricLoader.getInstance().getGameDir().toRealPath());
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
            }, IO);
        }
        return current;
    }

    private static Snapshot inspect(Catalog.Config config, Path root) throws Exception {
        Catalog.Release release = Catalog.fetch(config);
        Path home = UpdateWorker.directory(root.resolve(UpdateWorker.HOME));
        Path modFolder = UpdateWorker.directory(root.resolve("mods"));
        Path packFolder = UpdateWorker.directory(root.resolve("resourcepacks"));
        Map<String, List<Installed>> mods = scanMods(root, modFolder);
        Map<Path, Installed> modFiles = new HashMap<>();
        for (var list : mods.values()) {
            for (Installed mod : list) modFiles.put(mod.path(), mod);
        }

        Map<String, Row> rows = new LinkedHashMap<>();
        for (Catalog.Entry entry : release.entries().values()) {
            Installed installed = null;
            String problem = "";
            Path target = (entry.type().equals("mod") ? modFolder : packFolder).resolve(storedName(entry));
            if (entry.type().equals("mod")) {
                var candidates = new ArrayList<>(mods.getOrDefault(entry.id(), List.of()));
                if (entry.metadataOnly()) {
                    for (Path path : List.of(target, modFolder.resolve(entry.file()))) {
                        Installed mod = modFiles.get(path);
                        if (mod != null && !candidates.contains(mod)) candidates.add(mod);
                    }
                }
                if (candidates.size() > 1) problem = "同じ Mod ID のファイルが複数あります。";
                if (candidates.size() == 1) installed = candidates.get(0);
                if (candidates.isEmpty() && FabricLoader.getInstance().isModLoaded(entry.id())) {
                    problem = "この Mod は別の場所または他の JAR の内部から読み込まれています。";
                }
                if (entry.id().equals("seiyo_updater")) {
                    problem = "アップデーター本体は手動で更新してください。";
                }
            } else {
                Path zip = packFolder.resolve(entry.file());
                if (Files.isRegularFile(target, UpdateWorker.NO_LINKS)) {
                    var header = PackedArchive.header(target);
                    installed = new Installed(target, header.version(), header.hash(), "",
                        RuntimeFiles.cache(root, false).resolve(entry.file()));
                } else if (Files.isRegularFile(zip, UpdateWorker.NO_LINKS)) {
                    installed = new Installed(zip, "ZIP", "", "", zip);
                }
                if (UpdateWorker.exists(zip) && (installed == null || !installed.path().equals(zip))) {
                    problem = "保存先の名前が別のファイルと重複しています。";
                }
            }
            if (UpdateWorker.exists(target) && (installed == null || !installed.path().equals(target))) {
                problem = "保存先の名前が別のファイルと重複しています。";
            }

            String hash = "";
            if (installed != null) {
                String filename = installed.path().getFileName().toString();
                if (!UpdateWorker.validFilename(filename) && !UpdateWorker.validOldFilename(filename)) {
                    problem = "導入済みファイルの名前を確認してください。";
                }
                hash = UpdateWorker.hash(installed.path());
            }
            boolean mod = entry.type().equals("mod");
            boolean plainJar = mod && installed != null && !PackedArchive.isPacked(installed.path());
            boolean matched = installed != null && (mod ? plainJar : installed.path().equals(target))
                && (entry.metadataOnly()
                    ? Files.isRegularFile(installed.archive(), UpdateWorker.NO_LINKS)
                        && UpdateWorker.hash(installed.archive(), "SHA-512").equals(entry.sha512())
                    : (plainJar ? hash : installed.archiveHash()).equals(entry.hash())
                        && installed.version().equals(entry.version()));
            rows.put(entry.id(), new Row(entry, installed, hash, matched, problem));
        }
        Catalog.rememberRevision(home, config, release);
        return new Snapshot(root, release.key(), release.revision(), Collections.unmodifiableMap(rows));
    }

    private static Map<String, List<Installed>> scanMods(Path root, Path modFolder) throws Exception {
        Map<String, List<Installed>> mods = new HashMap<>();
        try (var paths = Files.list(modFolder)) {
            for (Path file : paths.sorted().toList()) {
                if (!Files.isRegularFile(file, UpdateWorker.NO_LINKS)) continue;
                String filename = file.getFileName().toString();
                if (PackedArchive.isPacked(file)) {
                    String id = filename.substring(0, filename.length() - PackedArchive.EXTENSION.length());
                    var header = PackedArchive.header(file);
                    Path runtime = RuntimeFiles.cache(root, true).resolve(id + ".jar");
                    JsonObject metadata = Files.isRegularFile(runtime, UpdateWorker.NO_LINKS)
                        ? zipJson(runtime, "fabric.mod.json") : null;
                    String actual = metadata == null ? id : Catalog.required(metadata, "id", 64);
                    String version = metadata == null ? header.version() : Catalog.required(metadata, "version", 100);
                    Installed installed = new Installed(file, version, header.hash(), actual, runtime);
                    mods.computeIfAbsent(id, key -> new ArrayList<>())
                        .add(installed);
                    if (!actual.equals(id)) mods.computeIfAbsent(actual, key -> new ArrayList<>()).add(installed);
                    continue;
                }
                if (!filename.toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
                JsonObject metadata;
                try {
                    metadata = zipJson(file, "fabric.mod.json");
                } catch (ZipException | RuntimeException ignored) {
                    continue;
                }
                if (metadata == null || !metadata.has("id") || !metadata.has("version")) continue;
                String id = metadata.get("id").getAsString();
                mods.computeIfAbsent(id, key -> new ArrayList<>())
                    .add(new Installed(file, metadata.get("version").getAsString(), "", id, file));
            }
        }
        return mods;
    }

    static String storedName(Catalog.Entry entry) {
        return entry.storedName();
    }

    static Set<String> defaults(Snapshot snapshot) {
        Set<String> selected = new HashSet<>();
        for (Row row : snapshot.rows.values()) {
            if (!row.matched && row.installed != null && row.problem.isEmpty()) selected.add(row.entry.id());
        }
        return selected;
    }

    static Plan plan(Snapshot snapshot, Set<String> selected) {
        Set<String> included = new HashSet<>();
        Set<String> locked = new HashSet<>();
        for (Row row : snapshot.rows.values()) {
            if (row.entry.required()) {
                locked.add(row.entry.id());
                include(snapshot, row.entry.id(), included, locked);
            }
        }
        for (String id : selected) include(snapshot, id, included, locked);
        List<Row> changes = snapshot.rows.values().stream()
            .filter(row -> included.contains(row.entry.id()) && !row.matched).toList();
        return new Plan(Set.copyOf(included), Set.copyOf(locked), changes);
    }

    private static void include(Snapshot snapshot, String id, Set<String> included, Set<String> locked) {
        if (!included.add(id)) return;
        for (String dep : snapshot.rows.get(id).entry.dependencies()) {
            locked.add(dep);
            include(snapshot, dep, included, locked);
        }
    }

    static CompletableFuture<Void> stage(Snapshot snapshot, Plan plan, Consumer<String> progress) {
        return CompletableFuture.runAsync(() -> {
            try {
                stageAndSchedule(snapshot, plan, progress);
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        }, IO);
    }

    private static void stageAndSchedule(Snapshot snapshot, Plan plan, Consumer<String> progress) throws Exception {
        Path home = UpdateWorker.directory(snapshot.root.resolve(UpdateWorker.HOME));
        Path pending = home.resolve(UpdateWorker.PENDING);
        if (UpdateWorker.exists(pending)) throw new IOException("適用待ちの更新があります。");
        if (plan.changes.isEmpty()) throw new IOException("選択された更新がありません。");
        for (Row row : plan.changes) {
            if (!row.problem.isEmpty()) throw new IOException(row.entry.name() + ": " + row.problem);
        }
        Path job = Files.createDirectory(home.resolve("job-" + UUID.randomUUID()));
        boolean launched = false;
        boolean registered = false;
        try {
            List<UpdateWorker.Change> changes = new ArrayList<>();
            Set<String> replacing = new HashSet<>();
            for (Row row : plan.changes) replacing.add(row.entry.id());
            Set<String> stagedModIds = new HashSet<>();
            Map<String, List<Installed>> mods = scanMods(snapshot.root,
                UpdateWorker.directory(snapshot.root.resolve("mods")));
            for (int i = 0; i < plan.changes.size(); i++) {
                Row row = plan.changes.get(i);
                Catalog.Entry entry = row.entry;
                progress.accept("取得中 " + (i + 1) + "/" + plan.changes.size() + " · " + entry.name());
                Path staged = job.resolve(i + ".stage");
                Path raw = job.resolve("verify.download");
                byte[] data = Catalog.artifact(snapshot.key, entry);
                Installed previousFile = row.installed;
                String previousHash = row.installedHash;
                try {
                    boolean modArchive = entry.type().equals("mod");
                    Path archive = modArchive ? staged : raw;
                    Files.write(archive, data, StandardOpenOption.CREATE_NEW);
                    String actualId = verifyArchive(archive, entry, snapshot, replacing);
                    if (actualId != null && !stagedModIds.add(actualId)) {
                        throw new IOException("同じ Mod を重複して登録しています: " + actualId);
                    }
                    if (actualId != null && entry.metadataOnly()) {
                        List<Installed> candidates = mods.getOrDefault(actualId, List.of()).stream()
                            .filter(mod -> mod.modId().equals(actualId)).toList();
                        if (candidates.size() > 1
                                || (candidates.isEmpty() && FabricLoader.getInstance().isModLoaded(actualId))
                                || (previousFile != null && !previousFile.modId().equals(actualId))) {
                            throw new IOException("既存の Mod と安全に置き換えられません: " + actualId);
                        }
                        if (previousFile == null && !candidates.isEmpty()) {
                            previousFile = candidates.get(0);
                            previousHash = UpdateWorker.hash(previousFile.path());
                        }
                    }
                    if (!modArchive) {
                        String archiveHash = entry.metadataOnly() ? Catalog.hex(data) : entry.hash();
                        PackedArchive.write(staged, entry.file(), entry.version(), archiveHash, data);
                    }
                } finally {
                    Files.deleteIfExists(raw);
                }
                String previous = previousFile == null ? "" : previousFile.path().getFileName().toString();
                changes.add(new UpdateWorker.Change(entry.folder(), previous, storedName(entry), previousHash,
                    UpdateWorker.hash(staged)));
            }
            UpdateWorker.save(job, changes);

            Path worker = job.resolve("seiyo-update-worker.jar");
            try (InputStream input = Updater.class.getResourceAsStream("/seiyo-update-worker.jar")) {
                if (input == null) throw new IOException("更新用プログラムが JAR にありません。");
                Files.copy(input, worker);
            }

            Files.writeString(job.resolve("pending.tmp"), job.getFileName().toString(), StandardOpenOption.CREATE_NEW);
            Files.move(job.resolve("pending.tmp"), pending, StandardCopyOption.ATOMIC_MOVE);
            registered = true;

            Path bin = Path.of(System.getProperty("java.home"), "bin");
            Path java = bin.resolve("java");
            if (Files.isRegularFile(bin.resolve("javaw.exe"))) java = bin.resolve("javaw.exe");
            else if (Files.isRegularFile(bin.resolve("java.exe"))) java = bin.resolve("java.exe");
            new ProcessBuilder(java.toString(), "-cp", worker.toString(), UpdateWorker.class.getName(),
                snapshot.root.toString(), Long.toString(ProcessHandle.current().pid()))
                .directory(snapshot.root.toFile()).redirectErrorStream(true)
                .redirectOutput(job.resolve("worker.log").toFile()).start();
            launched = true;
        } finally {
            if (!launched) {
                if (registered) Files.deleteIfExists(pending);
                try (var files = Files.list(job)) {
                    for (Path file : files.toList()) Files.deleteIfExists(file);
                }
                Files.delete(job);
            }
        }
    }

    private static String verifyArchive(Path path, Catalog.Entry entry, Snapshot snapshot,
            Set<String> replacing) throws Exception {
        if (entry.type().equals("mod")) {
            JsonObject data = zipJson(path, "fabric.mod.json");
            String actualId = data == null ? "" : Catalog.required(data, "id", 64);
            String actualVersion = data == null ? "" : Catalog.required(data, "version", 100);
            if (data == null || !actualId.matches("[a-z][a-z0-9_-]{1,63}") || actualId.equals("seiyo_updater")
                    || (!entry.metadataOnly() && (!entry.id().equals(actualId) || !entry.version().equals(actualVersion)))
                    || (data.has("environment") && "server".equals(data.get("environment").getAsString()))) {
                throw new IOException("Fabric Mod の ID、バージョン、対応環境を確認してください: " + entry.name());
            }
            JsonObject dependencies = data.getAsJsonObject("depends");
            if (dependencies != null) {
                for (String id : List.of("minecraft", "java", "fabricloader")) {
                    var dependency = FabricLoader.getInstance().getModContainer(id);
                    if (!dependencies.has(id) || dependency.isEmpty()) continue;
                    if (!matches(dependencies.get(id), dependency.get().getMetadata().getVersion())) {
                        throw new IOException(entry.name() + " が現在の " + id + " に対応していません。");
                    }
                }
                for (var dependency : dependencies.entrySet()) {
                    String id = dependency.getKey();
                    Row row = snapshot.rows.get(id);
                    if (row == null || !row.entry.type().equals("mod")) continue;
                    if (row.entry.metadataOnly() && replacing.contains(id)) continue;
                    String version = replacing.contains(id) ? row.entry.version() :
                        row.installed == null ? null : row.installed.version();
                    if (version == null || !matches(dependency.getValue(), Version.parse(version))) {
                        throw new IOException(entry.name() + " に対応する " + row.entry.name()
                            + " が選ばれていません。選択内容と配布元の依存設定を確認してください。");
                    }
                }
            }
            return actualId;
        } else {
            JsonObject data = zipJson(path, "pack.mcmeta");
            if (data == null || !data.has("pack") || !data.getAsJsonObject("pack").has("pack_format")
                    || !data.getAsJsonObject("pack").getAsJsonPrimitive("pack_format").isNumber()) {
                throw new IOException("ZIP の直下に有効な pack.mcmeta が必要です: " + entry.name());
            }
        }
        return null;
    }

    private static boolean matches(JsonElement value, Version installed) throws Exception {
        if (!value.isJsonArray()) return VersionPredicate.parse(value.getAsString()).test(installed);
        for (JsonElement alternative : value.getAsJsonArray()) {
            if (VersionPredicate.parse(alternative.getAsString()).test(installed)) return true;
        }
        return false;
    }

    private static JsonObject zipJson(Path path, String name) throws IOException {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            var entry = zip.getEntry(name);
            if (entry == null || entry.isDirectory()) return null;
            try (InputStream source = zip.getInputStream(entry)) {
                return Catalog.parse(Catalog.read(source, Catalog.MAX_JSON));
            }
        }
    }

    static String error(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        LOGGER.warn("Seiyo update operation failed", error);
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private Updater() {}
}
