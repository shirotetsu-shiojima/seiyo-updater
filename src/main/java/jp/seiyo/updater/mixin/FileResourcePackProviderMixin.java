package jp.seiyo.updater.mixin;

import java.io.File;
import java.util.function.Consumer;
import jp.seiyo.updater.PackProfiles;
import net.minecraft.resource.FileResourcePackProvider;
import net.minecraft.resource.ResourcePackProfile;
import net.minecraft.resource.ResourcePackSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FileResourcePackProvider.class)
abstract class FileResourcePackProviderMixin {
    @Shadow @Final private File packsFolder;
    @Shadow @Final private ResourcePackSource source;

    @Inject(method = "register", at = @At("RETURN"))
    private void seiyo$appendPacked(Consumer<ResourcePackProfile> profiles, ResourcePackProfile.Factory factory,
                                   CallbackInfo callback) {
        PackProfiles.append(packsFolder, source, profiles, factory);
    }
}
