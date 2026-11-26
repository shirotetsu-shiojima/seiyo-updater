package jp.seiyo.updater;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resource.ResourcePackProfile;
import net.minecraft.resource.ResourcePackSource;
import net.minecraft.resource.ZipResourcePack;

public final class PackProfiles {
    public static void append(File folder, ResourcePackSource source, Consumer<ResourcePackProfile> profiles,
                              ResourcePackProfile.Factory factory) {
        Path root = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        if (!folder.toPath().toAbsolutePath().normalize().equals(root.resolve("resourcepacks"))) return;
        File[] cached = RuntimeFiles.cache(root, false).toFile().listFiles(
            file -> file.isFile() && file.getName().endsWith(".zip"));
        if (cached == null) return;
        Arrays.sort(cached);
        for (File file : cached) {
            if (new File(folder, file.getName()).exists()) continue;
            ResourcePackProfile profile = ResourcePackProfile.of("file/" + file.getName(), false,
                () -> new ZipResourcePack(file), factory, ResourcePackProfile.InsertionPosition.TOP, source);
            if (profile != null) profiles.accept(profile);
        }
    }

    private PackProfiles() {}
}
