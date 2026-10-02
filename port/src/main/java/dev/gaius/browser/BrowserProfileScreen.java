package dev.gaius.browser;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerSkin;
import org.teavm.jso.JSBody;

/**
 * Vanilla-styled profile editor (username + custom skin) opened from the title screen.
 * Applying it swaps the running client's identity in place; no page reload is needed.
 */
public final class BrowserProfileScreen extends Screen {
    private static final int COLUMN_WIDTH = 200;
    private static final String NAME_PATTERN = "[A-Za-z0-9_]{1,16}";

    private final Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    private final boolean online;
    private EditBox nameBox;
    private Button modelButton;
    private MultiLineTextWidget status;
    private String pendingName;
    private String pendingSkin;
    private boolean pendingSlim;
    private boolean picking;
    private String previewKey = "";
    private Supplier<PlayerSkin> previewSkin;

    public BrowserProfileScreen(Screen parent) {
        super(Component.literal(tr("Edit Profile", "编辑个人资料")));
        this.parent = parent;
        this.online = BrowserProfile.isOnline();
        Minecraft client = Minecraft.getInstance();
        this.pendingName = client.getUser().getName();
        this.pendingSkin = BrowserProfile.savedSkinDataUrl();
        this.pendingSlim = BrowserProfile.savedSkinSlim();
    }

    /** "Edit Profile" button injected into the vanilla title screen (top-right corner). */
    public static Button titleButton(Screen title) {
        return Button.builder(Component.literal(tr("Edit Profile", "编辑个人资料")), ignored -> open(title))
                .bounds(title.width - 104, 6, 98, 20)
                .build();
    }

    /** Opens the editor once on first launch so a new player can pick a name and skin. */
    public static void titleTick(Screen title) {
        if (BrowserProfile.consumeFirstRun()) {
            open(title);
        }
    }

    public static void open(Screen parent) {
        Minecraft.getInstance().gui.setScreen(new BrowserProfileScreen(parent));
    }

    @Override
    protected void init() {
        layout.addTitleHeader(this.title, this.font);

        LinearLayout contents = layout.addToContents(LinearLayout.horizontal().spacing(16));
        contents.addChild(new PlayerSkinWidget(
                100, 150, this.minecraft.getEntityModels(), this::previewSkin));

        LinearLayout column = contents.addChild(LinearLayout.vertical().spacing(5));
        column.addChild(new StringWidget(Component.literal(tr("Username", "用户名")), this.font));
        nameBox = column.addChild(new EditBox(this.font, COLUMN_WIDTH, 20,
                Component.literal(tr("Username", "用户名"))));
        nameBox.setMaxLength(16);
        nameBox.setValue(pendingName);
        nameBox.setResponder(value -> pendingName = value);
        if (online) {
            nameBox.setEditable(false);
        }
        column.addChild(Button.builder(
                        Component.literal(tr("Upload Skin...", "上传皮肤...")),
                        ignored -> beginPick())
                .width(COLUMN_WIDTH)
                .build());
        modelButton = column.addChild(Button.builder(modelLabel(), ignored -> toggleModel())
                .width(COLUMN_WIDTH)
                .build());
        column.addChild(Button.builder(
                        Component.literal(tr("Use Default Skin", "使用默认皮肤")),
                        ignored -> useDefaultSkin())
                .width(COLUMN_WIDTH)
                .build());
        status = column.addChild(new MultiLineTextWidget(
                hint(online
                        ? tr("Online accounts keep their name; the skin can still change.",
                                "在线账号不能改名，但可以换皮肤。")
                        : tr("Changes apply the next time you join a world or server.",
                                "修改将在下次进入世界或服务器时生效。")),
                this.font).setMaxWidth(COLUMN_WIDTH));

        LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, ignored -> done()).width(150).build());
        footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, ignored -> onClose()).width(150).build());

        layout.visitWidgets(this::addRenderableWidget);
        refreshModelButton();
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
    }

    @Override
    public void tick() {
        super.tick();
        if (!picking) {
            return;
        }
        String state = BrowserProfile.pickState();
        if (state.contains("\"state\":\"pending\"") || state.contains("\"state\":\"idle\"")) {
            return;
        }
        picking = false;
        BrowserProfile.clearPickState();
        if (state.contains("\"state\":\"done\"")) {
            String dataUrl = jsonString(state, "dataUrl");
            if (!dataUrl.isEmpty()) {
                pendingSkin = dataUrl;
                pendingSlim = state.contains("\"slim\":true");
                refreshModelButton();
                setStatus(hint(tr("Skin loaded. Press Done to save it.", "皮肤已载入，点击完成保存。")));
            }
        } else if (state.contains("\"state\":\"error\"")) {
            setStatus(error(jsonString(state, "error")));
        }
    }

    @Override
    public void onClose() {
        BrowserProfile.clearPickState();
        markSetupComplete();
        this.minecraft.gui.setScreen(parent);
    }

    private void beginPick() {
        picking = true;
        setStatus(hint(tr("Choose a 64x64 or 64x32 PNG skin...", "请选择 64x64 或 64x32 的 PNG 皮肤...")));
        BrowserProfile.beginSkinPick();
    }

    private void toggleModel() {
        if (pendingSkin.isEmpty()) {
            return;
        }
        pendingSlim = !pendingSlim;
        refreshModelButton();
    }

    private void useDefaultSkin() {
        pendingSkin = "";
        pendingSlim = false;
        refreshModelButton();
        setStatus(hint(tr("Default skin selected. Press Done to save it.", "已选择默认皮肤，点击完成保存。")));
    }

    private void done() {
        String name = online ? this.minecraft.getUser().getName() : nameBox.getValue().trim();
        if (!name.matches(NAME_PATTERN)) {
            setStatus(error(tr("Use 1-16 letters, numbers or underscores.", "用户名须为 1-16 位字母、数字或下划线。")));
            return;
        }
        String failure = BrowserProfile.apply(this.minecraft, name, pendingSkin, pendingSlim);
        if (failure != null) {
            setStatus(error(failure));
            return;
        }
        markSetupComplete();
        this.minecraft.gui.setScreen(parent);
    }

    /** The first-launch editor was seen; later launches start without it. */
    @JSBody(script = """
            try {
              localStorage.removeItem('gaius.profileSetupPending');
            } catch (ignored) {
              // Storage can be unavailable; the editor then simply opens again next launch.
            }
            """)
    private static native void markSetupComplete();

    private PlayerSkin previewSkin() {
        String key = pendingSkin.length() + ":" + pendingSkin.hashCode() + ":" + pendingSlim;
        if (previewSkin == null || !key.equals(previewKey)) {
            previewKey = key;
            UUID id = this.minecraft.getUser().getProfileId();
            GameProfile profile = BrowserSkinProfile.withTextures(
                    id, this.minecraft.getUser().getName(),
                    BrowserProfile.texturesValue(pendingSkin, pendingSlim));
            previewSkin = this.minecraft.getSkinManager().createLookup(profile, false);
        }
        return previewSkin.get();
    }

    private void refreshModelButton() {
        if (modelButton != null) {
            modelButton.setMessage(modelLabel());
            modelButton.active = !pendingSkin.isEmpty();
        }
    }

    private Component modelLabel() {
        String model = pendingSlim ? tr("Slim", "纤细") : tr("Classic", "经典");
        return Component.literal(tr("Arms: ", "手臂：") + model);
    }

    private void setStatus(Component message) {
        if (status != null) {
            status.setMessage(message);
            repositionElements();
        }
    }

    private static Component hint(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    private static Component error(String text) {
        return Component.literal(text).withStyle(ChatFormatting.RED);
    }

    private static String tr(String english, String chinese) {
        String language = Minecraft.getInstance().options.languageCode;
        return language != null && language.startsWith("zh") ? chinese : english;
    }

    /** Minimal extraction of a JSON string field produced by JSON.stringify. */
    private static String jsonString(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            return "";
        }
        start += marker.length();
        StringBuilder out = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                out.append(json.charAt(++i));
            } else if (c == '"') {
                break;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
