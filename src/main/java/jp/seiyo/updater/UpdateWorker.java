package jp.seiyo.updater;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/** Standalone worker launched with the game's Java runtime. */
public final class UpdateWorker {
    static final String HOME = ".seiyo-updater";
    static final String PENDING = "pending";
    static final String LAST_APPLIED = "last-applied";
    static final LinkOption NO_LINKS = LinkOption.NOFOLLOW_LINKS;

    record Change(String folder, String previous, String next, String previousHash, String nextHash) {}

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[1].equals("--clean-after")) {
            waitFor(args[2]);
            Path home = directory(Path.of(args[0]).toRealPath().resolve(HOME));
            try (FileChannel channel = lockChannel(home); FileLock lock = channel.tryLock()) {
                if (lock != null && !exists(home.resolve(PENDING))) removeRuntimeCaches(home);
            }
            return;
        }
        if (args.length != 2) throw new IllegalArgumentException("Usage: ROOT PID|--recover");
        Path root = Path.of(args[0]).toRealPath();
        Path home = directory(root.resolve(HOME));
        try {
            if (!args[1].equals("--recover")) {
                waitFor(args[1]);
            }
            try (FileChannel channel = lockChannel(home); FileLock lock = waitForLock(channel)) {
                if (lock == null) throw new IOException("Minecraft is still running in this instance.");
                removeRuntimeCaches(home);
                applyPending(root, home);
            }
        } catch (Exception failure) {
            StringWriter trace = new StringWriter();
            failure.printStackTrace(new PrintWriter(trace));
            writeStatus(home, trace.toString());
            throw failure;
        }
    }

    private static void waitFor(String pid) {
        ProcessHandle.of(Long.parseLong(pid)).filter(ProcessHandle::isAlive)
            .ifPresent(process -> process.onExit().join());
    }

    private static FileLock waitForLock(FileChannel channel) throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            FileLock lock = channel.tryLock();
            if (lock != null) return lock;
            Thread.sleep(100);
        }
        return null;
    }

    private static void removeRuntimeCaches(Path home) {
        removeRuntimeCache(home.resolve("runtime-mods"), ".jar");
        removeRuntimeCache(home.resolve("runtime-packs"), ".zip");
    }

    private static void removeRuntimeCache(Path cache, String extension) {
        if (!Files.isDirectory(cache, NO_LINKS)) return;
        try (var files = Files.list(cache)) {
            for (Path file : files.toList()) {
                if (file.getFileName().toString().endsWith(extension)
                        && Files.isRegularFile(file, NO_LINKS)) Files.deleteIfExists(file);
            }
        } catch (IOException ignored) {
            // A reader outside Minecraft may temporarily keep the cache open.
        }
    }

    static FileChannel lockChannel(Path home) throws IOException {
        return FileChannel.open(home.resolve("instance.lock"), StandardOpenOption.CREATE,
            StandardOpenOption.WRITE, NO_LINKS);
    }

    static Path directory(Path path) throws IOException {
        if (!Files.exists(path, NO_LINKS)) {
            try {
                Files.createDirectory(path);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // An adjacent process may have created the directory first.
            }
        }
        if (!Files.isDirectory(path, NO_LINKS)) throw new IOException("Invalid directory: " + path);
        return path;
    }

    static boolean validFilename(String name) {
        return name.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,162}[A-Za-z0-9]")
            && !name.split("\\.", 2)[0].matches("(?i)CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]");
    }

    static boolean validOldFilename(String name) {
        return name.length() <= 255 && name.toLowerCase(Locale.ROOT).endsWith(".jar")
            && !name.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*");
    }

    static boolean exists(Path path) {
        return Files.exists(path, NO_LINKS);
    }

    static String hash(Path path) throws Exception {
        return hash(path, "SHA-256");
    }

    static String hash(Path path, String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (InputStream input = Files.newInputStream(path, NO_LINKS)) {
            byte[] buffer = new byte[65536];
            for (int length; (length = input.read(buffer)) != -1;) digest.update(buffer, 0, length);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static void save(Path job, List<Change> changes) throws IOException {
        Properties plan = new Properties();
        plan.setProperty("count", Integer.toString(changes.size()));
        for (int i = 0; i < changes.size(); i++) {
            Change item = changes.get(i);
            plan.setProperty(i + ".folder", item.folder);
            plan.setProperty(i + ".previous", item.previous);
            plan.setProperty(i + ".next", item.next);
            plan.setProperty(i + ".previousHash", item.previousHash);
            plan.setProperty(i + ".nextHash", item.nextHash);
        }
        try (var out = Files.newOutputStream(job.resolve("plan.properties"), StandardOpenOption.CREATE_NEW)) {
            plan.store(out, "Seiyo update");
        }
        load(job);
    }

    private static List<Change> load(Path job) throws IOException {
        Properties plan = new Properties();
        try (var input = Files.newInputStream(job.resolve("plan.properties"), NO_LINKS)) {
            plan.load(input);
        }
        int count = Integer.parseInt(plan.getProperty("count"));
        if (count < 1 || count > 250) throw new IOException("Invalid update plan size");
        List<Change> items = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        for (int i = 0; i < count; i++) {
            Change item = new Change(plan.getProperty(i + ".folder"), plan.getProperty(i + ".previous"),
                plan.getProperty(i + ".next"), plan.getProperty(i + ".previousHash"),
                plan.getProperty(i + ".nextHash"));
            if (!Set.of("mods", "resourcepacks").contains(item.folder)
                    || !validFilename(item.next) || !item.nextHash.matches("[a-f0-9]{64}")
                    || (!item.previous.isEmpty() && (!(validFilename(item.previous)
                        || item.folder.equals("mods") && validOldFilename(item.previous))
                        || !item.previousHash.matches("[a-f0-9]{64}")))) {
                throw new IOException("Invalid update plan entry");
            }
            String prefix = item.folder + "/";
            if (!paths.add(prefix + item.next.toLowerCase(Locale.ROOT))
                    || (!item.previous.isEmpty() && !item.previous.equalsIgnoreCase(item.next)
                        && !paths.add(prefix + item.previous.toLowerCase(Locale.ROOT)))) {
                throw new IOException("Conflicting paths in update plan");
            }
            items.add(item);
        }
        return items;
    }

    static void applyPending(Path root, Path home) throws Exception {
        Path pending = home.resolve(PENDING);
        if (!exists(pending)) return;
        if (!Files.isRegularFile(pending, NO_LINKS) || Files.size(pending) > 80) {
            throw new IOException("Invalid pending update");
        }
        String jobName = Files.readString(pending).trim();
        if (!jobName.matches("job-[a-f0-9-]{36}")) throw new IOException("Invalid pending update name");
        Path job = directory(home.resolve(jobName));
        Path committed = job.resolve("committed");
        Path canceled = job.resolve("canceled");
        if (exists(committed) || exists(canceled)) {
            if (exists(committed)) finish(home);
            Files.delete(pending);
            return;
        }

        List<Change> items = load(job);
        List<Integer> applied = new ArrayList<>();
        try {
            // Include interrupted entries in rollback, even if an earlier entry fails.
            for (int i = 0; i < items.size(); i++) {
                if (exists(job.resolve(i + ".backup")) || !exists(job.resolve(i + ".stage"))) applied.add(i);
            }
            for (int i = 0; i < items.size(); i++) {
                if (!applied.contains(i)) applied.add(i);
                applyOne(root, job, i, items.get(i));
            }
            Files.createFile(committed);
        } catch (Exception failure) {
            Collections.reverse(applied);
            boolean restored = true;
            for (int i : applied) {
                try {
                    restoreOne(root, job, i, items.get(i));
                } catch (Exception rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                    restored = false;
                }
            }
            if (restored) {
                Files.createFile(canceled);
                Files.delete(pending);
            }
            throw failure;
        }
        finish(home);
        Files.delete(pending);
        // Backups remain in the completed job for manual recovery.
    }

    private static void applyOne(Path root, Path job, int index, Change item) throws Exception {
        Path folder = directory(root.resolve(item.folder));
        Path next = folder.resolve(item.next);
        Path stage = job.resolve(index + ".stage");
        Path backup = job.resolve(index + ".backup");
        if (!exists(stage)) {
            verify(next, item.nextHash);
            if (!item.previous.isEmpty()) verify(backup, item.previousHash);
            return;
        }
        verify(stage, item.nextHash);
        if (!item.previous.isEmpty()) {
            Path previous = folder.resolve(item.previous);
            if (exists(backup)) {
                verify(backup, item.previousHash);
            } else {
                verify(previous, item.previousHash);
                if (!item.previous.equalsIgnoreCase(item.next) && exists(next)) {
                    throw new IOException("Destination already exists: " + next);
                }
                move(previous, backup);
            }
        }
        move(stage, next);
    }

    private static void restoreOne(Path root, Path job, int index, Change item) throws Exception {
        Path folder = directory(root.resolve(item.folder));
        Path next = folder.resolve(item.next);
        Path stage = job.resolve(index + ".stage");
        Path backup = job.resolve(index + ".backup");
        if (!exists(stage)) {
            verify(next, item.nextHash);
            move(next, stage);
        }
        if (exists(backup)) {
            verify(backup, item.previousHash);
            move(backup, folder.resolve(item.previous));
        }
    }

    private static void verify(Path path, String expected) throws Exception {
        if (!Files.isRegularFile(path, NO_LINKS) || !hash(path).equals(expected)) {
            throw new IOException("File missing or changed: " + path);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        if (exists(target)) throw new IOException("Destination already exists: " + target);
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    static void writeStatus(Path home, String message) throws IOException {
        Files.writeString(home.resolve("last-result.txt"), message, StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, NO_LINKS);
    }

    private static void finish(Path home) throws IOException {
        Files.writeString(home.resolve(LAST_APPLIED), "applied", StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, NO_LINKS);
        writeStatus(home, "更新を適用しました。");
    }

    private UpdateWorker() {}
}
