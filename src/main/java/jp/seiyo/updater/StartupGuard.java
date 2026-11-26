package jp.seiyo.updater;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

public final class StartupGuard implements PreLaunchEntrypoint {
    // Keep the instance exclusively locked until this JVM exits.
    private static FileChannel channel;
    private static FileLock lock;

    @Override
    public void onPreLaunch() {
        try {
            if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
                ServerStartup.run(FabricLoader.getInstance().getGameDir().toRealPath());
                return;
            }
            Path home = UpdateWorker.directory(FabricLoader.getInstance().getGameDir()
                .toRealPath().resolve(UpdateWorker.HOME));
            Path root = home.getParent();
            String bootstrap = System.getProperty(RuntimeFiles.AGENT_ROOT);
            if (bootstrap == null) {
                channel = UpdateWorker.lockChannel(home);
                lock = channel.tryLock();
            } else if (!root.equals(Path.of(bootstrap))) {
                throw new IllegalStateException("ブートストラップとランチャーのゲームフォルダーが異なります。");
            }
            Path timestamp = home.resolve(UpdateWorker.LAST_APPLIED);
            Instant started = ProcessHandle.current().info().startInstant().orElse(Instant.MAX);
            if ((bootstrap == null && lock == null) || UpdateWorker.exists(home.resolve(UpdateWorker.PENDING))
                    || (Files.isRegularFile(timestamp, UpdateWorker.NO_LINKS)
                        && Files.getLastModifiedTime(timestamp, UpdateWorker.NO_LINKS).toInstant().isAfter(started))) {
                throw new IllegalStateException(
                    "別の星陽クライアントが起動中か、更新を適用中です。数秒後に再起動してください。"
                    + " 続く場合は .seiyo-updater/last-result.txt を確認してください。");
            }
            if (bootstrap == null) {
                try (var paths = Files.list(UpdateWorker.directory(root.resolve("mods")))) {
                    if (paths.anyMatch(PackedArchive::isPacked)) {
                        throw new IllegalStateException("SYP の Mod を読み込むには星陽ブートストラップが必要です。");
                    }
                }
            }
            try (var jobs = Files.list(home)) {
                for (Path job : jobs.toList()) {
                    if (job.getFileName().toString().matches("job-[a-f0-9-]{36}")
                            && Files.isDirectory(job, UpdateWorker.NO_LINKS)) {
                        Files.deleteIfExists(job.resolve("verify.download"));
                    }
                }
            }
            RuntimeFiles.prepare(root, false);
        } catch (Exception failure) {
            throw new IllegalStateException("星陽アップデーターがこのゲームフォルダーを使用できません。", failure);
        }
    }
}
