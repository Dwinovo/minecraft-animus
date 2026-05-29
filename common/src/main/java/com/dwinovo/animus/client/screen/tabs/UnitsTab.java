package com.dwinovo.animus.client.screen.tabs;

import com.dwinovo.animus.client.agent.AgentLoopRegistry;
import com.dwinovo.animus.client.data.ClientPlayerAnimusState;
import com.dwinovo.animus.client.data.ClientUnitView;
import com.dwinovo.animus.client.screen.AnimusManagerScreen;
import com.dwinovo.animus.client.screen.EntityChatScreen;
import com.dwinovo.animus.client.screen.SimpleButton;
import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.network.payload.RecallUnitPayload;
import com.dwinovo.animus.network.payload.SummonUnitPayload;
import com.dwinovo.animus.network.payload.UnitConfigUpdatePayload;
import com.dwinovo.animus.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * Units tab — list 6 unit slots on the left, show / edit the selected
 * unit's config on the right.
 *
 * <h2>What's editable</h2>
 * <ul>
 *   <li>{@code name} — player-set display name (sent in env block as
 *       {@code name="..."}; LLM can use it to identify by nickname).</li>
 *   <li>{@code model_key} — bedrock model id (e.g. {@code animus:hachiware}
 *       or {@code user:my_custom}). Currently text input; a dropdown of
 *       available models comes in a later commit.</li>
 * </ul>
 * {@code alive} and {@code active} are read-only — driven by the server.
 *
 * <h2>3D preview</h2>
 * Deferred to a follow-up — would need entity-in-GUI rendering plumbing
 * (analogous to vanilla {@code InventoryScreen.renderEntityInInventory}).
 * For MVP a textual status block covers the same need.
 */
public final class UnitsTab extends Tab {

    private static final int ROW_HEIGHT = 22;
    private static final int LIST_WIDTH = 110;
    private static final int PADDING = 6;
    private static final int INPUT_HEIGHT = 18;

    private int selectedUnitId = 1;

    private int contentX, contentY, contentWidth, contentHeight;
    private EditBox nameInput;
    private EditBox modelInput;

    public UnitsTab(AnimusManagerScreen parent) {
        super(parent);
    }

    @Override
    public Component title() {
        return Component.literal("Units");
    }

    @Override
    public void onEnter(int x, int y, int width, int height) {
        this.contentX = x;
        this.contentY = y;
        this.contentWidth = width;
        this.contentHeight = height;
        Font font = Minecraft.getInstance().font;

        // -- Left: 6 unit-row buttons --
        int rowX = x + PADDING;
        int rowY = y + PADDING;
        for (int i = 1; i <= 6; i++) {
            final int unitId = i;
            ClientUnitView view = ClientPlayerAnimusState.instance().unit(unitId);
            String label = view.displayLabel() + statusSuffix(view);
            SimpleButton btn = new SimpleButton(rowX, rowY + (i - 1) * (ROW_HEIGHT - 2),
                    LIST_WIDTH, ROW_HEIGHT - 4,
                    Component.literal(label),
                    b -> selectUnit(unitId));
            if (unitId == selectedUnitId) btn.active = false;
            parent.registerTabWidget(btn);
        }

        // -- Right: edit panel --
        int panelX = x + LIST_WIDTH + PADDING * 3;
        int panelY = y + PADDING + 14;
        int panelWidth = width - LIST_WIDTH - PADDING * 4;

        ClientUnitView selected = ClientPlayerAnimusState.instance().unit(selectedUnitId);

        this.nameInput = new EditBox(font, panelX, panelY, panelWidth, INPUT_HEIGHT,
                Component.literal("name"));
        this.nameInput.setMaxLength(64);
        this.nameInput.setValue(selected.name() == null ? "" : selected.name());
        this.nameInput.setHint(Component.literal("(unnamed)"));
        parent.registerTabWidget(this.nameInput);

        panelY += INPUT_HEIGHT + 14;
        this.modelInput = new EditBox(font, panelX, panelY, panelWidth, INPUT_HEIGHT,
                Component.literal("model"));
        this.modelInput.setMaxLength(128);
        this.modelInput.setValue(selected.modelKey());
        this.modelInput.setHint(Component.literal("animus:hachiware"));
        parent.registerTabWidget(this.modelInput);

        panelY += INPUT_HEIGHT + 14;
        // Apply button
        SimpleButton applyBtn = new SimpleButton(panelX, panelY, 80, INPUT_HEIGHT,
                Component.literal("Apply"),
                b -> applyEdits());
        parent.registerTabWidget(applyBtn);

        // -- Action row: Summon (idle) / Recall + Chat (busy) --
        panelY += INPUT_HEIGHT + 30;  // clear the status line drawn in render()
        int actionW = 56;
        if (selected.active()) {
            SimpleButton recallBtn = new SimpleButton(panelX, panelY, actionW, INPUT_HEIGHT,
                    Component.literal("Recall"), b -> recallUnit());
            parent.registerTabWidget(recallBtn);
            SimpleButton chatBtn = new SimpleButton(panelX + actionW + 4, panelY, actionW, INPUT_HEIGHT,
                    Component.literal("Chat"), b -> openChat());
            parent.registerTabWidget(chatBtn);
        } else if (selected.alive()) {
            SimpleButton summonBtn = new SimpleButton(panelX, panelY, actionW + 24, INPUT_HEIGHT,
                    Component.literal("Summon"), b -> summonUnit());
            parent.registerTabWidget(summonBtn);
        }
    }

    private void summonUnit() {
        Services.NETWORK.sendToServer(new SummonUnitPayload(selectedUnitId));
        // Server replies with UnitSpawnedPayload → snapshot refresh repaints the row.
    }

    private void recallUnit() {
        Services.NETWORK.sendToServer(new RecallUnitPayload(selectedUnitId));
    }

    /** Open the per-entity chat for the unit currently occupying this slot. */
    private void openChat() {
        AgentLoopRegistry.entityIdForUnit(selectedUnitId).ifPresent(entityId -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;
            if (mc.level.getEntity(entityId) instanceof AnimusEntity entity) {
                EntityChatScreen.open(entity);
            }
        });
    }

    private void selectUnit(int unitId) {
        this.selectedUnitId = unitId;
        // Rebuild parent to refresh active-button states and load fresh edit values.
        parent.rebuildAll();
    }

    private void applyEdits() {
        String name = nameInput.getValue().trim();
        String model = modelInput.getValue().trim();
        if (model.isEmpty()) model = "animus:hachiware";
        Services.NETWORK.sendToServer(new UnitConfigUpdatePayload(selectedUnitId, name, model));
        // Optimistic local update — server snapshot will overwrite shortly.
        ClientPlayerAnimusState state = ClientPlayerAnimusState.instance();
        ClientUnitView view = state.unit(selectedUnitId);
        state.setUnit(selectedUnitId, view.withName(name.isEmpty() ? null : name).withModelKey(model));
    }

    private static String statusSuffix(ClientUnitView view) {
        if (!view.alive()) return "  [dead]";
        return view.active() ? "  [busy]" : "  [idle]";
    }

    @Override
    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        Font font = Minecraft.getInstance().font;

        // Title above left list
        g.text(font, Component.literal("Units"),
                contentX + PADDING, contentY - 2, 0xFFAAAAAA);

        // Right-panel field labels
        int panelX = contentX + LIST_WIDTH + PADDING * 3;
        int panelY = contentY + PADDING;
        g.text(font, Component.literal("Unit " + selectedUnitId + " — edit"),
                panelX, panelY, 0xFFFFFFFF);

        panelY += 14;
        g.text(font, Component.literal("Name"),
                panelX, panelY - 12, 0xFFAAAAAA);

        panelY += INPUT_HEIGHT + 14;
        g.text(font, Component.literal("Model key"),
                panelX, panelY - 12, 0xFFAAAAAA);

        panelY += INPUT_HEIGHT + 14 + INPUT_HEIGHT + 6;
        // Status line under the Apply button
        ClientUnitView v = ClientPlayerAnimusState.instance().unit(selectedUnitId);
        String status = "alive: " + v.alive() + "    active: " + v.active();
        g.text(font, Component.literal(status), panelX, panelY, 0xFF888888);
    }

}
