package jp.seiyo.updater;

import java.io.File;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

public final class ArchiveBootstrap {
    private static FileChannel channel;
    private static FileLock lock;

    public static void premain(String instance) throws Exception {
        if (instance == null || instance.isBlank()) {
            throw new IllegalArgumentException("Set -javaagent:<bootstrap.jar>=<Minecraft instance folder>");
        }
        Path root = Path.of(instance).toRealPath();
        if (root.toString().contains(File.pathSeparator)) {
            throw new IllegalArgumentException("The instance path contains the platform classpath separator.");
        }
        Path home = UpdateWorker.directory(root.resolve(UpdateWorker.HOME));
        channel = UpdateWorker.lockChannel(home);
        lock = channel.tryLock();
        if (lock == null || UpdateWorker.exists(home.resolve(UpdateWorker.PENDING))) {
            throw new IllegalStateException("Another Minecraft process or a Seiyo update is still running.");
        }
        String paths = RuntimeFiles.prepare(root, true).stream().map(Path::toString)
            .collect(Collectors.joining(File.pathSeparator));
        if (!paths.isEmpty()) {
            String previous = System.getProperty("fabric.addMods", "");
            System.setProperty("fabric.addMods", previous.isBlank() ? paths : previous + File.pathSeparator + paths);
            if (File.separatorChar == '\\') scheduleWindowsCleanup(root);
        }
        System.setProperty(RuntimeFiles.AGENT_ROOT, root.toString());
    }

    private static void scheduleWindowsCleanup(Path root) {
        try {
            Path java = Path.of(System.getProperty("java.home"), "bin", "javaw.exe");
            if (!Files.isRegularFile(java)) java = java.resolveSibling("java.exe");
            Path bootstrap = Path.of(ArchiveBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            new ProcessBuilder(java.toString(), "-cp", bootstrap.toString(), UpdateWorker.class.getName(),
                root.toString(), "--clean-after", Long.toString(ProcessHandle.current().pid()))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (Exception ignored) {
            // Cache recovery on the next startup also covers launchers that restrict subprocesses.
        }
    }

    private ArchiveBootstrap() {}
}
