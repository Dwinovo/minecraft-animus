package com.dwinovo.animus.client.screen;

import com.dwinovo.animus.client.screen.tabs.StorageTab;
import com.dwinovo.animus.client.screen.tabs.Tab;
import com.dwinovo.animus.client.screen.tabs.UnitsTab;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The single in-game GUI for managing Animus units. Top tab bar (Units /
 * Storage), top-right buttons (Settings / Close), content area below.
 *
 * <p>Chat is no longer a tab — in the single-layer agent design each Animus
 * has its own conversation, reached by right-clicking the entity (or the
 * Units tab Chat button), which opens {@link EntityChatScreen}.
 *
 * <h2>Why one screen</h2>
 * Multiple separate screens (one per concern) would lose state when the
 * player tabs between them (e.g. typing a chat message then checking the
 * storage tab and coming back). One screen with sub-tabs preserves
 * everything in field state.
 *
 * <h2>Tab switching</h2>
 * {@link #setActiveTab} simply re-calls {@link #init()} after updating the
 * active-tab field. Widget clearance is automatic — {@code Screen.init} is
 * called before children are populated, so adding the nav-bar buttons and
 * the new tab's widgets builds a fresh widget set per switch.
 *
 * <h2>Reaching settings</h2>
 * The pre-existing {@link SettingsScreen} is preserved verbatim — opened
 * from the Settings button as a sub-screen with {@code parent = this} so
 * its close button returns to this manager screen.
 */
public final class AnimusManagerScreen extends Screen {

    public enum TabId { UNITS, STORAGE }

    public static final int CONTENT_WIDTH = 360;
    public static final int CONTENT_HEIGHT = 240;
    private static final int TAB_HEIGHT = 18;
    private static final int TOP_BUTTONS_HEIGHT = 16;
    private static final int PADDING = 8;

    private TabId activeTab = TabId.UNITS;
    private final List<Tab> tabs = new ArrayList<>();
    private final UnitsTab unitsTab;
    private final StorageTab storageTab;

    private AnimusManagerScreen() {
        super(Component.literal("Animus"));
        this.unitsTab = new UnitsTab(this);
        this.storageTab = new StorageTab(this);
        tabs.add(unitsTab);
        tabs.add(storageTab);
    }

    /** Open the manager. Typically wired to {@code /animus} (no args). */
    public static void open() {
        Minecraft.getInstance().setScreen(new AnimusManagerScreen());
    }

    public TabId activeTab() { return activeTab; }

    public void setActiveTab(TabId id) {
        if (id != activeTab) {
            this.activeTab = id;
            this.rebuildWidgets();
        }
    }

    /** Expose to tabs so they can register their own widgets. */
    public <T extends net.minecraft.client.gui.components.events.GuiEventListener & net.minecraft.client.gui.components.Renderable & net.minecraft.client.gui.narration.NarratableEntry>
            T registerTabWidget(T widget) {
        return addRenderableWidget(widget);
    }

    /** Public alias for the tabs to request a full refresh (e.g. after selection change). */
    public void rebuildAll() {
        this.rebuildWidgets();
    }

    @Override
    protected void init() {
        int left = (this.width - CONTENT_WIDTH) / 2;
        int top  = (this.height - CONTENT_HEIGHT) / 2;

        // Top tab bar (3 tabs, equal width).
        int tabsTotalWidth = CONTENT_WIDTH - 130;  // leave room for the right-side action buttons
        int tabWidth = tabsTotalWidth / tabs.size();
        for (int i = 0; i < tabs.size(); i++) {
            final TabId id = TabId.values()[i];
            int bx = left + i * tabWidth;
            SimpleButton b = new SimpleButton(bx, top, tabWidth, TAB_HEIGHT,
                    tabs.get(i).title(),
                    btn -> setActiveTab(id));
            if (id == activeTab) b.active = false;
            addRenderableWidget(b);
        }

        // Top-right action buttons: [Settings] [Close]
        int actionWidth = 60;
        int closeX = left + CONTENT_WIDTH - actionWidth;
        int settingsX = closeX - actionWidth - 4;
        addRenderableWidget(new SimpleButton(settingsX, top, actionWidth, TAB_HEIGHT,
                Component.literal("Settings"),
                b -> SettingsScreen.open(this)));
        addRenderableWidget(new SimpleButton(closeX, top, actionWidth, TAB_HEIGHT,
                Component.literal("Close"),
                b -> this.onClose()));

        // Hand off to active tab.
        int contentX = left;
        int contentY = top + TAB_HEIGHT + PADDING;
        int contentH = CONTENT_HEIGHT - TAB_HEIGHT - PADDING;
        tabs.get(activeTab.ordinal()).onEnter(contentX, contentY, CONTENT_WIDTH, contentH);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        super.extractRenderState(g, mouseX, mouseY, partial);

        int left = (this.width - CONTENT_WIDTH) / 2;
        int top  = (this.height - CONTENT_HEIGHT) / 2;

        // Content panel backdrop (under the tab bar).
        int panelTop = top + TAB_HEIGHT;
        g.fill(left, panelTop, left + CONTENT_WIDTH, top + CONTENT_HEIGHT, 0xA0000000);
        g.outline(left, panelTop, CONTENT_WIDTH, CONTENT_HEIGHT - TAB_HEIGHT, 0x55FFFFFF);

        tabs.get(activeTab.ordinal()).render(g, mouseX, mouseY, partial);
    }

    @Override
    public void tick() {
        super.tick();
        tabs.get(activeTab.ordinal()).tick();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Internal — used by tabs that need access to the underlying widget list. */
    public List<AbstractWidget> exposeChildren() {
        List<AbstractWidget> out = new ArrayList<>();
        for (var c : this.children()) {
            if (c instanceof AbstractWidget aw) out.add(aw);
        }
        return out;
    }
}
