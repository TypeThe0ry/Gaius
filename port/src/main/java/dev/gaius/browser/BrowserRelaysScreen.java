package dev.gaius.browser;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Vanilla-styled relay manager opened from the multiplayer screen. User relays can be added,
 * edited, removed and moved up (earlier rows are tried first); the build's built-in relays can be
 * switched off and on. Each relay is used for multiplayer, for Open to LAN, or for both.
 */
public final class BrowserRelaysScreen extends Screen {
    private static final int ROW_HEIGHT = 30;
    private static final int TOP = 34;

    private final Screen parent;
    private final List<BrowserRelays.Relay> relays = new ArrayList<>();
    private int page;
    private Component status = Component.empty();

    public BrowserRelaysScreen(Screen parent) {
        super(Component.literal(tr("Relays", "中继")));
        this.parent = parent;
    }

    /** "Relays" button injected into the vanilla multiplayer screen (top-right corner). */
    public static Button joinButton(Screen screen) {
        Button button = Button.builder(Component.literal(tr("Relays", "中继")),
                        ignored -> Minecraft.getInstance().gui.setScreen(new BrowserRelaysScreen(screen)))
                .bounds(screen.width - 104, 6, 98, 20)
                .build();
        button.setTooltip(Tooltip.create(Component.literal(tr(
                "Choose the relays used for multiplayer and Open to LAN",
                "选择多人游戏和局域网联机使用的中继"))));
        button.visible = BrowserRelays.available();
        return button;
    }

    @Override
    protected void init() {
        relays.clear();
        relays.addAll(BrowserRelays.list());
        int rowsPerPage = rowsPerPage();
        int pages = Math.max(1, (relays.size() + rowsPerPage - 1) / rowsPerPage);
        page = Math.max(0, Math.min(page, pages - 1));

        addRenderableWidget(centered(this.title, 12));

        int rowWidth = Math.min(this.width - 24, 420);
        int left = (this.width - rowWidth) / 2;
        int firstUser = firstUserIndex();
        int start = page * rowsPerPage;
        for (int slot = 0; slot < rowsPerPage && start + slot < relays.size(); slot++) {
            int index = start + slot;
            BrowserRelays.Relay relay = relays.get(index);
            int y = TOP + slot * ROW_HEIGHT;
            int buttonsWidth = relay.builtIn() ? 58 : (index > firstUser ? 104 : 80);
            int textWidth = rowWidth - buttonsWidth - 6;
            addRenderableWidget(new StringWidget(left, y, textWidth, 10, nameLine(relay), this.font)
                    .setMaxWidth(textWidth));
            addRenderableWidget(new StringWidget(left, y + 12, textWidth, 10, urlLine(relay), this.font)
                    .setMaxWidth(textWidth));
            int x = left + rowWidth - buttonsWidth;
            if (relay.builtIn()) {
                addRenderableWidget(Button.builder(
                                Component.literal(relay.enabled() ? tr("Disable", "停用") : tr("Enable", "启用")),
                                ignored -> toggleBuiltIn(relay))
                        .bounds(x, y, 58, 20).build());
                continue;
            }
            if (index > firstUser) {
                addRenderableWidget(Button.builder(Component.literal("▲"), ignored -> moveUp(index))
                        .bounds(x, y, 20, 20)
                        .tooltip(Tooltip.create(Component.literal(tr("Try this relay earlier", "提高优先级"))))
                        .build());
                x += 24;
            }
            addRenderableWidget(Button.builder(Component.literal(tr("Edit", "编辑")), ignored -> edit(index))
                    .bounds(x, y, 52, 20).build());
            addRenderableWidget(Button.builder(Component.literal("X"), ignored -> remove(index))
                    .bounds(x + 56, y, 20, 20)
                    .tooltip(Tooltip.create(Component.literal(tr("Remove relay", "删除中继"))))
                    .build());
        }
        if (relays.isEmpty()) {
            addRenderableWidget(centered(
                    Component.literal(tr("No relays configured.", "尚未配置中继。")).withStyle(ChatFormatting.GRAY),
                    TOP + 8));
        }
        if (pages > 1) {
            int y = TOP + rowsPerPage * ROW_HEIGHT;
            addRenderableWidget(Button.builder(Component.literal("<"), ignored -> turnPage(-1))
                    .bounds(this.width / 2 - 60, y, 20, 20).build()).active = page > 0;
            addRenderableWidget(centered(Component.literal((page + 1) + " / " + pages), y + 6));
            addRenderableWidget(Button.builder(Component.literal(">"), ignored -> turnPage(1))
                    .bounds(this.width / 2 + 40, y, 20, 20).build()).active = page < pages - 1;
        }
        addRenderableWidget(centered(status, this.height - 66));
        addRenderableWidget(Button.builder(Component.literal(tr("Add relay", "添加中继")), ignored -> add())
                .bounds(this.width / 2 - 100, this.height - 52, 200, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, ignored -> onClose())
                .bounds(this.width / 2 - 100, this.height - 28, 200, 20).build());
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    Screen parentScreen() {
        return parent;
    }

    private StringWidget centered(Component text, int y) {
        int textWidth = Math.max(1, this.font.width(text));
        return new StringWidget((this.width - textWidth) / 2, y, textWidth, 10, text, this.font);
    }

    private int rowsPerPage() {
        return Math.max(1, (this.height - TOP - 96) / ROW_HEIGHT);
    }

    private int firstUserIndex() {
        for (int index = 0; index < relays.size(); index++) {
            if (!relays.get(index).builtIn()) {
                return index;
            }
        }
        return relays.size();
    }

    private Component nameLine(BrowserRelays.Relay relay) {
        MutableComponent line = Component.literal(relay.name().isEmpty() ? relay.url() : relay.name())
                .withStyle(relay.enabled() ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY);
        line.append(Component.literal("  [" + useLabel(relay.use()) + "]").withStyle(ChatFormatting.GRAY));
        if (relay.builtIn()) {
            line.append(Component.literal("  " + tr("built-in", "内置")).withStyle(ChatFormatting.DARK_AQUA));
        }
        if (!relay.enabled()) {
            line.append(Component.literal("  " + tr("disabled", "已停用")).withStyle(ChatFormatting.RED));
        }
        return line;
    }

    private static Component urlLine(BrowserRelays.Relay relay) {
        MutableComponent line = Component.literal(relay.secure() ? "WSS " : "WS ")
                .withStyle(relay.secure() ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
        line.append(Component.literal(relay.url()).withStyle(ChatFormatting.GRAY));
        return line;
    }

    static String useLabel(String use) {
        return switch (BrowserRelays.normalizeUse(use)) {
            case BrowserRelays.USE_MULTIPLAYER -> tr("Multiplayer", "多人游戏");
            case BrowserRelays.USE_LAN -> tr("LAN", "局域网");
            default -> tr("Both", "全部");
        };
    }

    private void turnPage(int delta) {
        page += delta;
        rebuildWidgets();
    }

    private void toggleBuiltIn(BrowserRelays.Relay relay) {
        BrowserRelays.setBuiltInEnabled(relay.url(), !relay.enabled());
        status = Component.literal(tr("Saved. Applies to the next connection.", "已保存，下次连接时生效。"))
                .withStyle(ChatFormatting.GRAY);
        rebuildWidgets();
    }

    private void moveUp(int index) {
        List<BrowserRelays.Relay> next = new ArrayList<>(relays);
        BrowserRelays.Relay moved = next.remove(index);
        next.add(index - 1, moved);
        save(next);
    }

    private void remove(int index) {
        List<BrowserRelays.Relay> next = new ArrayList<>(relays);
        next.remove(index);
        save(next);
    }

    private void edit(int index) {
        BrowserRelays.Relay relay = relays.get(index);
        this.minecraft.gui.setScreen(new BrowserRelayEditScreen(this, relay, updated -> {
            List<BrowserRelays.Relay> next = new ArrayList<>(relays);
            next.set(index, updated);
            return saveResult(next);
        }));
    }

    private void add() {
        this.minecraft.gui.setScreen(new BrowserRelayEditScreen(this, null, created -> {
            List<BrowserRelays.Relay> next = new ArrayList<>(relays);
            next.add(firstUserIndex(), created);
            return saveResult(next);
        }));
    }

    private void save(List<BrowserRelays.Relay> next) {
        String failure = saveResult(next);
        status = failure == null
                ? Component.literal(tr("Saved. Applies to the next connection.", "已保存，下次连接时生效。"))
                        .withStyle(ChatFormatting.GRAY)
                : Component.literal(failure).withStyle(ChatFormatting.RED);
        rebuildWidgets();
    }

    /** Saves the user relays; returns an error message or null. */
    private String saveResult(List<BrowserRelays.Relay> next) {
        String result = BrowserRelays.saveUser(next);
        return result == null || result.isEmpty() || "ok".equals(result) ? null : result;
    }

    static String tr(String english, String chinese) {
        String language = Minecraft.getInstance().options.languageCode;
        return language != null && language.startsWith("zh") ? chinese : english;
    }
}
