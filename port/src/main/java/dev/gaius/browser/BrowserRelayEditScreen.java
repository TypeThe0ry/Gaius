package dev.gaius.browser;

import java.util.function.Function;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Adds or edits one user relay: name, address and what it is used for. */
public final class BrowserRelayEditScreen extends Screen {
    private static final int FIELD_WIDTH = 300;

    private final Screen parent;
    private final BrowserRelays.Relay original;
    private final Function<BrowserRelays.Relay, String> onSave;
    private EditBox nameBox;
    private EditBox urlBox;
    private Button useButton;
    private String use;
    private String nameValue;
    private String urlValue;
    private Component status = Component.empty();

    public BrowserRelayEditScreen(Screen parent, BrowserRelays.Relay original,
            Function<BrowserRelays.Relay, String> onSave) {
        super(Component.literal(original == null
                ? BrowserRelaysScreen.tr("Add relay", "添加中继")
                : BrowserRelaysScreen.tr("Edit relay", "编辑中继")));
        this.parent = parent;
        this.original = original;
        this.onSave = onSave;
        this.use = original == null ? BrowserRelays.USE_BOTH : original.use();
        this.nameValue = original == null ? "" : original.name();
        this.urlValue = original == null ? "wss://" : original.url();
    }

    @Override
    protected void init() {
        int left = (this.width - FIELD_WIDTH) / 2;
        int y = Math.max(24, this.height / 2 - 82);
        addRenderableWidget(centered(this.title, y - 18));

        addRenderableWidget(new StringWidget(left, y, FIELD_WIDTH, 10,
                Component.literal(BrowserRelaysScreen.tr("Name", "名称")), this.font));
        nameBox = addRenderableWidget(new EditBox(this.font, left, y + 12, FIELD_WIDTH, 20,
                Component.literal(BrowserRelaysScreen.tr("Name", "名称"))));
        nameBox.setMaxLength(80);
        nameBox.setValue(nameValue);
        nameBox.setResponder(value -> nameValue = value);

        addRenderableWidget(new StringWidget(left, y + 40, FIELD_WIDTH, 10,
                Component.literal(BrowserRelaysScreen.tr("Address (wss://host/tunnel)", "地址（wss://主机/tunnel）")),
                this.font));
        urlBox = addRenderableWidget(new EditBox(this.font, left, y + 52, FIELD_WIDTH, 20,
                Component.literal(BrowserRelaysScreen.tr("Address", "地址"))));
        urlBox.setMaxLength(512);
        urlBox.setValue(urlValue);
        urlBox.setResponder(value -> urlValue = value);

        useButton = addRenderableWidget(Button.builder(useMessage(), ignored -> cycleUse())
                .bounds(left, y + 82, FIELD_WIDTH, 20).build());

        addRenderableWidget(centered(status, y + 110));
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, ignored -> save())
                .bounds(this.width / 2 - 154, y + 128, 150, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, ignored -> onClose())
                .bounds(this.width / 2 + 4, y + 128, 150, 20).build());
        setInitialFocus(nameBox);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    private StringWidget centered(Component text, int y) {
        int textWidth = Math.max(1, this.font.width(text));
        return new StringWidget((this.width - textWidth) / 2, y, textWidth, 10, text, this.font);
    }

    private Component useMessage() {
        return Component.literal(BrowserRelaysScreen.tr("Used for: ", "用途：")
                + BrowserRelaysScreen.useLabel(use));
    }

    private void cycleUse() {
        use = switch (use) {
            case BrowserRelays.USE_BOTH -> BrowserRelays.USE_MULTIPLAYER;
            case BrowserRelays.USE_MULTIPLAYER -> BrowserRelays.USE_LAN;
            default -> BrowserRelays.USE_BOTH;
        };
        useButton.setMessage(useMessage());
    }

    private void save() {
        String url = BrowserRelays.normalize(urlValue);
        if (url.isEmpty()) {
            fail(BrowserRelaysScreen.tr("Enter a ws:// or wss:// relay address.", "请输入 ws:// 或 wss:// 中继地址。"));
            return;
        }
        String name = nameValue.trim();
        String failure = onSave.apply(new BrowserRelays.Relay(url, name, use, false, true));
        if (failure != null) {
            fail(failure);
            return;
        }
        this.minecraft.gui.setScreen(new BrowserRelaysScreen(
                parent instanceof BrowserRelaysScreen relaysScreen ? relaysScreen.parentScreen() : parent));
    }

    private void fail(String message) {
        status = Component.literal(message).withStyle(ChatFormatting.RED);
        rebuildWidgets();
    }
}
