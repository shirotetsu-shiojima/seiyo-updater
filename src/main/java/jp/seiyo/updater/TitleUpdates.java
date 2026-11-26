package jp.seiyo.updater;

import java.util.concurrent.CompletableFuture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public final class TitleUpdates {
    private static final Text DEFAULT = Text.literal("星陽アップデート");
    private static CompletableFuture<Updater.Snapshot> displayed;
    private static Text label = DEFAULT;
    private static boolean offeredSetup;

    public static void open(Screen parent) {
        MinecraftClient.getInstance().setScreen(hasConfig() ? new UpdateScreen(parent) : new SetupScreen(parent));
    }

    public static boolean offerSetup(Screen parent) {
        if (offeredSetup || hasConfig() || MinecraftClient.getInstance().currentScreen != parent) return false;
        offeredSetup = true;
        open(parent);
        return true;
    }

    private static boolean hasConfig() {
        return java.nio.file.Files.exists(Updater.configPath(), UpdateWorker.NO_LINKS);
    }

    public static Text label() {
        if (!hasConfig()) return DEFAULT;
        var check = Updater.check(false);
        if (check == displayed || !check.isDone()) return label;
        displayed = check;
        label = DEFAULT;
        if (!check.isCompletedExceptionally()) {
            var snapshot = check.getNow(null);
            long count = Updater.plan(snapshot, Updater.defaults(snapshot)).changes().stream()
                .filter(row -> row.problem().isEmpty()).count();
            if (count > 0) label = Text.literal("星陽アップデート (" + count + ")");
        }
        return label;
    }

    private TitleUpdates() {}
}
