package jp.seiyo.updater;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

final class UpdateScreen extends Screen {
    private static final int ROW_HEIGHT = 30;
    private static final int LIST_Y = 56;
    private static final int INK = 0xffedf6fb;
    private static final int MUTED = 0xffb6c9d8;
    private static final int ACCENT = 0xff7ce8ce;

    private final Screen parent;
    private Updater.Snapshot snapshot;
    private Updater.Plan plan;
    private Set<String> selected = Set.of();
    private List<Updater.Row> rows = List.of();
    private Updater.Row details;
    private int page;
    private int detailPage;
    private int left;
    private int right;
    private boolean busy = true;
    private boolean installing;
    private String status = "配布先を確認しています…";

    UpdateScreen(Screen parent) {
        super(Text.literal("星陽アップデート"));
        this.parent = parent;
        fetch(false);
    }

    private void fetch(boolean refresh) {
        MinecraftClient game = MinecraftClient.getInstance();
        busy = true;
        snapshot = null;
        details = null;
        rows = List.of();
        plan = null;
        status = "配布先を確認しています…";
        Updater.check(refresh).whenComplete((result, error) -> game.execute(() -> {
            busy = false;
            if (error == null) {
                snapshot = result;
                selected = Updater.defaults(snapshot);
                rows = new ArrayList<>(snapshot.rows().values());
                page = 0;
                status = "";
                calculate();
            } else {
                status = Updater.error(error);
            }
            if (game.currentScreen == this) clearAndInit();
        }));
    }

    private void calculate() {
        plan = Updater.plan(snapshot, selected);
        long blocked = plan.included().stream().filter(id -> !snapshot.rows().get(id).problem().isEmpty()).count();
        status = blocked > 0 ? "確認が必要な項目 " + blocked + " 件" :
            plan.changes().isEmpty() ? "適用する更新はありません。" : "適用予定 " + plan.changes().size() + " 件";
    }

    private int pageSize() {
        return Math.max(1, (height - LIST_Y - 66) / ROW_HEIGHT);
    }

    private int pages() {
        return Math.max(1, (rows.size() + pageSize() - 1) / pageSize());
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(570, width - 20);
        left = (width - panelWidth) / 2;
        right = left + panelWidth;
        int footer = height - 35;

        if (details != null) {
            button(left + 8, footer, 65, "一覧へ", () -> { details = null; clearAndInit(); });
            int maxPage = Math.max(0, (detailLines().size() - 1) / detailPageSize());
            detailPage = Math.min(detailPage, maxPage);
            button(right - 66, footer, 27, "‹", () -> { detailPage--; clearAndInit(); }).active = detailPage > 0;
            button(right - 35, footer, 27, "›", () -> { detailPage++; clearAndInit(); }).active = detailPage < maxPage;
            return;
        }

        button(left + 8, footer, 46, "戻る", this::close).active = !installing;
        button(left + 58, footer, 48, "再確認", () -> { fetch(true); clearAndInit(); }).active = !busy;
        if (snapshot == null && !busy) {
            button(right - 106, footer, 98, "設定フォルダー", () -> {
                try {
                    java.nio.file.Files.createDirectories(Updater.configPath().getParent());
                    Util.getOperatingSystem().open(Updater.configPath().getParent().toFile());
                } catch (Exception error) {
                    status = Updater.error(error);
                }
            });
        }

        if (plan == null) return;
        page = Math.min(page, pages() - 1);
        int start = page * pageSize();
        for (int i = start; i < Math.min(rows.size(), start + pageSize()); i++) {
            Updater.Row row = rows.get(i);
            String id = row.entry().id();
            int y = LIST_Y + (i - start) * ROW_HEIGHT;
            String label = row.matched() ? "済" : plan.locked().contains(id) ? "必須" :
                plan.included().contains(id) ? "選択" : "追加";
            button(left + 9, y + 5, 35, label, () -> {
                if (!selected.remove(id)) selected.add(id);
                calculate();
                clearAndInit();
            }).active = !busy && !row.matched() && row.problem().isEmpty() && !plan.locked().contains(id);
            button(right - 45, y + 5, 36, "詳細", () -> {
                details = row;
                detailPage = 0;
                clearAndInit();
            }).active = !installing;
        }
        int center = width / 2;
        button(center - 44, height - 59, 25, "‹", () -> step(-1)).active = page > 0 && !installing;
        button(center + 19, height - 59, 25, "›", () -> step(1)).active = page + 1 < pages() && !installing;

        boolean blocked = plan.included().stream().anyMatch(id -> !snapshot.rows().get(id).problem().isEmpty());
        button(right - 130, footer, 122, "取得してゲームを終了", this::apply)
            .active = !busy && !blocked && !plan.changes().isEmpty();
    }

    private ButtonWidget button(int x, int y, int buttonWidth, String label, Runnable action) {
        return addDrawableChild(new ButtonWidget(x, y, buttonWidth, 20, Text.literal(label), ignored -> action.run()));
    }

    private void step(int amount) {
        int next = Math.max(0, Math.min(page + amount, pages() - 1));
        if (next != page) {
            page = next;
            clearAndInit();
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double amount) {
        if (!installing && details == null && pages() > 1 && amount != 0) {
            step(amount < 0 ? 1 : -1);
            return true;
        }
        return super.mouseScrolled(x, y, amount);
    }

    @Override
    public void close() {
        if (details != null) {
            details = null;
            clearAndInit();
        } else if (!installing) {
            client.setScreen(parent);
        }
    }

    private void apply() {
        busy = true;
        installing = true;
        status = "更新を準備しています…";
        clearAndInit();
        MinecraftClient game = client;
        Updater.stage(snapshot, plan, message -> game.execute(() -> status = message))
            .whenComplete((ignored, error) -> game.execute(() -> {
                if (error == null) {
                    status = "ダウンロードが完了しました。終了後に更新します。";
                    game.scheduleStop();
                } else {
                    status = Updater.error(error);
                    busy = false;
                    installing = false;
                    clearAndInit();
                }
            }));
    }

    private String summary(Updater.Row row) {
        if (!row.problem().isEmpty()) return row.problem();
        if (row.matched()) return row.entry().version() + " · 最新";
        if (row.installed() != null) {
            boolean packed = PackedArchive.isPacked(row.installed().path());
            if (row.entry().type().equals("mod") && packed) return row.entry().version() + " · JAR へ移行";
            if (row.entry().type().equals("pack") && !packed && row.installed().version().equals(row.entry().version())) {
                return row.entry().version() + " · SYP に変換";
            }
        }
        return (row.installed() == null ? "未導入" : row.installed().version())
            + "  →  " + row.entry().version();
    }

    private int detailPageSize() {
        return Math.max(1, (height - LIST_Y - 47) / 12);
    }

    private List<OrderedText> detailLines() {
        Catalog.Entry entry = details.entry();
        String dependencies = entry.dependencies().isEmpty() ? "なし" : String.join("、",
            entry.dependencies().stream().map(id -> snapshot.rows().get(id).entry().name() + " (" + id + ")").toList());
        String detail = (entry.type().equals("mod") ? "Mod" : "リソースパック") + " · " + entry.id() + "\n"
            + summary(details) + "\n"
            + "保存先  " + entry.folder() + "/" + Updater.storedName(entry) + "\n"
            + "依存先  " + dependencies + "\n"
            + (entry.size() / 1024 + " KiB") + " · " + (entry.encrypted() ? "暗号化配布" : "公開配布")
            + (entry.notes().isEmpty() ? "" : "\n\n" + entry.notes());
        return textRenderer.wrapLines(Text.literal(detail), right - left - 32);
    }

    private void line(MatrixStack matrices, String value, int x, int y, int limit, int color) {
        String display = value.replace('\n', ' ').replace('\r', ' ');
        if (textRenderer.getWidth(display) > limit) display = textRenderer.trimToWidth(display, Math.max(0, limit - 10)) + "…";
        textRenderer.drawWithShadow(matrices, display, x, y, color);
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        fillGradient(matrices, 0, 0, width, height, 0xff0a1725, 0xff182741);
        fill(matrices, left, 12, right, height - 8, 0xff132438);
        fill(matrices, left, 12, right, 14, ACCENT);
        int inner = right - left - 32;
        line(matrices, details == null ? "星陽アップデート" : details.entry().name(), left + 16, 23, inner, INK);
        line(matrices, "Minecraft 1.19.2 / Fabric" + (snapshot == null ? "" : "   ·   配布 #" + snapshot.revision()),
            left + 16, 39, inner, ACCENT);

        if (details != null) {
            List<OrderedText> lines = detailLines();
            int start = detailPage * detailPageSize();
            for (int i = start; i < Math.min(lines.size(), start + detailPageSize()); i++) {
                textRenderer.drawWithShadow(matrices, lines.get(i), left + 16, LIST_Y + (i - start) * 12, INK);
            }
        } else {
            int start = page * pageSize();
            for (int i = start; i < Math.min(rows.size(), start + pageSize()); i++) {
                Updater.Row row = rows.get(i);
                int y = LIST_Y + (i - start) * ROW_HEIGHT;
                fill(matrices, left + 6, y, right - 6, y + ROW_HEIGHT - 2, 0xff203951);
                line(matrices, row.entry().name(), left + 51, y + 4, right - left - 103, INK);
                line(matrices, (row.entry().type().equals("mod") ? "Mod" : "パック") + "  ·  " + summary(row),
                    left + 51, y + 15, right - left - 103, row.problem().isEmpty() ? MUTED : 0xffffac99);
            }
            if (pages() > 1) drawCenteredText(matrices, textRenderer, (page + 1) + "/" + pages(),
                width / 2, height - 53, MUTED);
            int y = snapshot == null ? LIST_Y + 5 : height - 17;
            if (snapshot == null) {
                List<OrderedText> lines = textRenderer.wrapLines(Text.literal(status), inner);
                for (OrderedText text : lines) {
                    textRenderer.drawWithShadow(matrices, text, left + 16, y, INK);
                    y += 12;
                    if (y > height - 50) break;
                }
            } else {
                line(matrices, status, left + 16, y, inner, INK);
            }
        }
        super.render(matrices, mouseX, mouseY, delta);
    }
}
