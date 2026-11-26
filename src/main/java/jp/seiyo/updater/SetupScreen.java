package jp.seiyo.updater;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

final class SetupScreen extends Screen {
    private final Screen parent;
    private TextFieldWidget url;
    private ButtonWidget connect;
    private boolean busy;
    private String message = "";

    SetupScreen(Screen parent) {
        super(Text.literal("星陽アップデートの初期設定"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        String previous = url == null ? "" : url.getText();
        int size = Math.min(width - 36, 390);
        url = addDrawableChild(new TextFieldWidget(textRenderer, (width - size) / 2, height / 2 - 20,
            size, 22, Text.literal("配布マニフェストの URL")));
        url.setMaxLength(2048);
        url.setText(previous);
        url.setEditable(!busy);
        addDrawableChild(new ButtonWidget(width / 2 - 108, height / 2 + 15, 102, 20,
            Text.literal("あとで"), button -> close())).active = !busy;
        connect = addDrawableChild(new ButtonWidget(width / 2 + 6, height / 2 + 15, 102, 20,
            Text.literal("取得画面を開く"), button -> submit()));
        connect.active = !busy && !previous.isBlank();
        url.setChangedListener(value -> connect.active = !busy && !value.isBlank());
        setInitialFocus(url);
    }

    @Override
    public void tick() {
        url.tick();
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && connect.active && url.isFocused()) {
            submit();
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    public void close() {
        if (!busy) client.setScreen(parent);
    }

    private void submit() {
        busy = true;
        message = "配布先を確認しています…";
        String value = url.getText();
        clearAndInit();
        var game = client;
        Updater.configure(value).whenComplete((result, error) -> game.execute(() -> {
            if (game.currentScreen != this) return;
            if (error == null) {
                game.setScreen(new UpdateScreen(parent));
                return;
            }
            busy = false;
            message = Updater.error(error);
            clearAndInit();
        }));
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        renderBackground(matrices);
        drawCenteredText(matrices, textRenderer, title, width / 2, height / 2 - 55, 0xffffff);
        drawCenteredText(matrices, textRenderer, Text.literal("配布された manifest.json の HTTPS URL を貼り付けてください。"),
            width / 2, height / 2 - 37, 0xb6c9d8);
        int y = height / 2 + 49;
        for (var line : textRenderer.wrapLines(Text.literal(message), width - 36)) {
            if (y > height - 12) break;
            textRenderer.drawWithShadow(matrices, line, (width - textRenderer.getWidth(line)) / 2f,
                y, busy ? 0xb6c9d8 : 0xffffac99);
            y += 12;
        }
        super.render(matrices, mouseX, mouseY, delta);
    }
}
