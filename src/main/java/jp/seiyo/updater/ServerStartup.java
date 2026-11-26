package jp.seiyo.updater;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class ServerStartup {
    // Hold the instance lock until this process and its optional child terminate.
    private static FileChannel channel;
    private static FileLock lock;

    static void run(Path root) throws Exception {
        Path home = UpdateWorker.directory(root.resolve(UpdateWorker.HOME));
        if (root.toString().equals(System.getenv(ServerUpdates.PREUPDATED))) return;
        channel = UpdateWorker.lockChannel(home);
        lock = channel.tryLock();
        if (lock == null) throw new IOException("別の星陽プロセスがこのサーバーを使用しています。");

        if (!UpdateWorker.exists(home.resolve(UpdateWorker.PENDING)) && !ServerUpdates.stage(root, home, true)) {
            return;
        }
        List<String> command = relaunchCommand(ProcessHandle.current().info());
        UpdateWorker.applyPending(root, home);
        System.out.println("必須 Mod の更新を適用しました。更新済みの構成でサーバーを起動します。");
        System.exit(ServerUpdates.launch(root, command));
    }

    private static List<String> relaunchCommand(ProcessHandle.Info process) throws IOException {
        String message = "起動コマンドを再取得できません。seiyo-tools.jar の server コマンドで起動してください。";
        String executable = process.command().orElseThrow(() -> new IOException(message));
        String[] arguments = process.arguments().orElseThrow(() -> new IOException(message));
        if (arguments.length == 0) throw new IOException(message);
        List<String> command = new ArrayList<>(arguments.length + 1);
        command.add(executable);
        command.addAll(List.of(arguments));
        return command;
    }

    private ServerStartup() {}
}
