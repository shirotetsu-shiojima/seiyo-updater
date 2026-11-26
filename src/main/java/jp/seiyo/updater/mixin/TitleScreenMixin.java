package jp.seiyo.updater.mixin;

import jp.seiyo.updater.TitleUpdates;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
abstract class TitleScreenMixin extends Screen {
    @Unique private ButtonWidget seiyo$button;

    protected TitleScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void seiyo$addUpdater(CallbackInfo callback) {
        seiyo$button = addDrawableChild(new ButtonWidget(width - 114, height - 45, 106, 20,
            Text.literal("星陽アップデート"), button -> TitleUpdates.open(this)));
        TitleUpdates.label();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void seiyo$displayAvailability(CallbackInfo callback) {
        if (seiyo$button == null || TitleUpdates.offerSetup(this)) return;
        Text label = TitleUpdates.label();
        if (!seiyo$button.getMessage().equals(label)) seiyo$button.setMessage(label);
    }
}
