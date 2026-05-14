package com.dwinovo.animus.client.screen;

import com.dwinovo.animus.anim.api.ModelLibrary;
import com.dwinovo.animus.anim.compile.ConfigModelLoader;
import com.dwinovo.animus.data.ModLanguageData;
import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.entity.InitEntity;
import com.dwinovo.animus.network.payload.SetModelPayload;
import com.dwinovo.animus.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Sneaking-right-click GUI for swapping an {@link AnimusEntity}'s rendered
 * model. Left side lists every {@link ModelLibrary baked model} (mod
 * built-ins under {@code animus:} plus player-supplied models under
 * {@code animus_user:}); right side renders a live 3D preview of the
 * selected model on a transient client-only {@link AnimusEntity}.
 *
 * <h2>Layout</h2>
 * Built around vanilla {@link Button}s rather than {@code ObjectSelectionList}
 * — the list is small (handfuls of models), and a paged button stack matches
 * the existing chiikawa music-box screen pattern, keeping the codebase
 * consistent.
 *
 * <h2>Buttons</h2>
 * <ul>
 *   <li><b>Refresh</b> — runs {@link ConfigModelLoader#rescan(Path)} so newly
 *       dropped files in {@code <gameDir>/config/animus/models/} appear
 *       without a {@code /reload} or restart. Only the {@code animus_user}
 *       namespace is touched.</li>
 *   <li><b>Apply</b> — dispatches a {@link SetModelPayload} C→S; the
 *       server-side handler validates proximity and writes the synced
 *       EntityData, which vanilla broadcasts to every tracking client.</li>
 *   <li><b>Cancel</b> — closes without sending anything.</li>
 * </ul>
 *
 * <h2>Why this Screen lives in common</h2>
 * {@code net.minecraft.client.gui.screens.Screen} is part of the shared
 * client jar both loaders bundle; common code can reference it as long as
 * the JVM only loads the class client-side. {@link AnimusEntity#mobInteract}
 * guards the open call with {@code level().isClientSide()}, so the dedicated
 * server never sees this class.
 */
public final class ChooseModelScreen extends Screen {

    private static final int CONTENT_WIDTH    = 320;
    private static final int CONTENT_HEIGHT   = 220;
    private static final int LIST_WIDTH       = 160;
    private static final int LIST_ROW_HEIGHT  = 22;
    private static final int LIST_ROW_GAP     = 2;
    private static final int LIST_VISIBLE_ROWS = 6;
    private static final int BUTTON_HEIGHT    = 20;
    private static final int PAGE_BUTTON_WIDTH = 32;
    private static final int FOOTER_BUTTON_WIDTH = 80;
    private static final int FOOTER_BUTTON_GAP   = 4;
    private static final int PREVIEW_PADDING  = 6;
    private static final int PREVIEW_SCALE    = 50;
    private static final float PREVIEW_Y_OFFSET = 0.0625f;

    private final AnimusEntity target;
    private final Path configDir;

    /** Snapshot of the model list at GUI-open / refresh time. */
    private List<ModelEntry> entries = List.of();
    private @Nullable ModelEntry selected;
    private int page;
    private Component statusMessage = Component.empty();

    /** Lazy client-only preview entity, re-created when the selection changes. */
    private @Nullable AnimusEntity previewEntity;
    private @Nullable Identifier previewedKey;

    public ChooseModelScreen(AnimusEntity target, Path configDir) {
        super(Component.translatable(ModLanguageData.Keys.GUI_CHOOSE_MODEL_TITLE));
        this.target = target;
        this.configDir = configDir;
    }

    /** Convenience entry point used by {@link AnimusEntity#mobInteract}. */
    public static void open(AnimusEntity entity) {
        Minecraft mc = Minecraft.getInstance();
        Path configDir = mc.gameDirectory.toPath()
                .resolve("config").resolve("animus").resolve("models");
        mc.setScreen(new ChooseModelScreen(entity, configDir));
    }

    @Override
    protected void init() {
        this.entries = snapshotEntries();
        if (this.selected == null) {
            this.selected = entries.stream()
                    .filter(e -> e.id().equals(target.getModelKey()))
                    .findFirst().orElse(entries.isEmpty() ? null : entries.get(0));
        }
        clampPage();
        rebuildModelButtons();
    }

    private static List<ModelEntry> snapshotEntries() {
        return new ArrayList<>(ModelLibrary.keys().stream()
                .sorted(Comparator.<Identifier, String>comparing(Identifier::getNamespace)
                        .thenComparing(Identifier::getPath))
                .map(ModelEntry::of)
                .toList());
    }

    private void rebuildModelButtons() {
        clearWidgets();

        int left = (this.width - CONTENT_WIDTH) / 2;
        int top  = (this.height - CONTENT_HEIGHT) / 2;
        int listTop = top;

        // Model entry buttons (one per visible row this page).
        int rowStart = page * LIST_VISIBLE_ROWS;
        int rowEnd = Math.min(entries.size(), rowStart + LIST_VISIBLE_ROWS);
        for (int i = rowStart; i < rowEnd; i++) {
            ModelEntry entry = entries.get(i);
            int y = listTop + (i - rowStart) * (LIST_ROW_HEIGHT + LIST_ROW_GAP);
            Button btn = Button.builder(rowLabel(entry), b -> select(entry))
                    .bounds(left, y, LIST_WIDTH, LIST_ROW_HEIGHT)
                    .build();
            // The selection is marked inactive so it visually pops and can't
            // be re-clicked as a no-op.
            if (selected != null && selected.id().equals(entry.id())) {
                btn.active = false;
            }
            addRenderableWidget(btn);
        }

        // Page navigation (only when more than one page exists).
        int pages = totalPages();
        if (pages > 1) {
            int navY = listTop + LIST_VISIBLE_ROWS * (LIST_ROW_HEIGHT + LIST_ROW_GAP);
            Button prev = Button.builder(Component.literal("<"), b -> {
                if (page > 0) { page--; rebuildModelButtons(); }
            }).bounds(left, navY, PAGE_BUTTON_WIDTH, BUTTON_HEIGHT).build();
            prev.active = page > 0;
            addRenderableWidget(prev);

            Button next = Button.builder(Component.literal(">"), b -> {
                if (page + 1 < totalPages()) { page++; rebuildModelButtons(); }
            }).bounds(left + LIST_WIDTH - PAGE_BUTTON_WIDTH, navY,
                    PAGE_BUTTON_WIDTH, BUTTON_HEIGHT).build();
            next.active = page + 1 < pages;
            addRenderableWidget(next);
        }

        // Footer button row: [Refresh] ... [Cancel][Apply].
        int footerY = top + CONTENT_HEIGHT - BUTTON_HEIGHT;
        addRenderableWidget(Button.builder(
                Component.translatable(ModLanguageData.Keys.GUI_CHOOSE_MODEL_REFRESH),
                this::onRefresh)
                .bounds(left, footerY, FOOTER_BUTTON_WIDTH, BUTTON_HEIGHT).build());

        int rightButtonsLeft = left + CONTENT_WIDTH - FOOTER_BUTTON_WIDTH * 2 - FOOTER_BUTTON_GAP;
        addRenderableWidget(Button.builder(
                Component.translatable(ModLanguageData.Keys.GUI_CHOOSE_MODEL_CANCEL),
                b -> this.onClose())
                .bounds(rightButtonsLeft, footerY, FOOTER_BUTTON_WIDTH, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(
                Component.translatable(ModLanguageData.Keys.GUI_CHOOSE_MODEL_APPLY),
                this::onApply)
                .bounds(rightButtonsLeft + FOOTER_BUTTON_WIDTH + FOOTER_BUTTON_GAP, footerY,
                        FOOTER_BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    private Component rowLabel(ModelEntry entry) {
        // Single-line "Name (Namespace)" — keeps the namespace visible without
        // needing a custom Button subclass for multi-line rendering.
        return Component.empty().append(entry.name())
                .append(Component.literal(" ("))
                .append(entry.namespaceLabel())
                .append(Component.literal(")"));
    }

    private int totalPages() {
        return Math.max(1, (entries.size() + LIST_VISIBLE_ROWS - 1) / LIST_VISIBLE_ROWS);
    }

    private void clampPage() {
        int pages = totalPages();
        if (page < 0) page = 0;
        if (page >= pages) page = pages - 1;
        // Jump to the page containing the selection if it isn't already visible.
        if (selected != null) {
            int idx = indexOfSelected();
            if (idx >= 0) page = idx / LIST_VISIBLE_ROWS;
        }
    }

    private int indexOfSelected() {
        if (selected == null) return -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(selected.id())) return i;
        }
        return -1;
    }

    private void select(ModelEntry entry) {
        this.selected = entry;
        this.statusMessage = Component.empty();
        rebuildModelButtons();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        int left = (this.width - CONTENT_WIDTH) / 2;
        int top  = (this.height - CONTENT_HEIGHT) / 2;

        graphics.centeredText(this.font, this.getTitle(),
                this.width / 2, top - 14, 0xFFFFFFFF);

        int previewLeft   = left + LIST_WIDTH + PREVIEW_PADDING;
        int previewTop    = top;
        int previewRight  = left + CONTENT_WIDTH;
        int previewBottom = top + CONTENT_HEIGHT - BUTTON_HEIGHT - PREVIEW_PADDING;
        renderPreview(graphics, previewLeft, previewTop, previewRight, previewBottom, mouseX, mouseY);

        if (!Component.empty().equals(statusMessage)) {
            graphics.centeredText(this.font, statusMessage,
                    this.width / 2, top - 2, 0xFFAAAAAA);
        }
        if (selected != null && !Component.empty().equals(selected.description())) {
            graphics.centeredText(this.font, selected.description(),
                    (previewLeft + previewRight) / 2, previewBottom + 4, 0xFFAAAAAA);
        }
        if (entries.isEmpty()) {
            graphics.centeredText(this.font,
                    Component.translatable(ModLanguageData.Keys.GUI_CHOOSE_MODEL_NO_MODELS),
                    left + LIST_WIDTH / 2, top + 40, 0xFFAAAAAA);
        }
    }

    private void renderPreview(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2,
                                int mouseX, int mouseY) {
        graphics.fill(x1, y1, x2, y2, 0xFF1A1A1A);
        graphics.outline(x1, y1, x2 - x1, y2 - y1, 0xFF555555);

        if (selected == null || this.minecraft == null || this.minecraft.level == null) return;

        AnimusEntity preview = ensurePreviewEntity(selected.id());
        if (preview == null) return;

        float relMouseX = (x1 + x2) / 2.0f - mouseX;
        float relMouseY = (y1 + y2) / 2.0f - 50 - mouseY;
        InventoryScreen.extractEntityInInventoryFollowsMouse(
                graphics, x1, y1, x2, y2,
                PREVIEW_SCALE, PREVIEW_Y_OFFSET, relMouseX, relMouseY, preview);
    }

    private @Nullable AnimusEntity ensurePreviewEntity(Identifier modelKey) {
        if (this.minecraft == null || this.minecraft.level == null) return null;
        if (InitEntity.ANIMUS == null) return null;
        if (previewEntity != null && modelKey.equals(previewedKey)) return previewEntity;

        previewEntity = new AnimusEntity(InitEntity.ANIMUS.get(), this.minecraft.level);
        previewEntity.setModelKey(modelKey);
        previewedKey = modelKey;
        return previewEntity;
    }

    private void onRefresh(Button btn) {
        ConfigModelLoader.RescanStats stats = ConfigModelLoader.rescan(configDir);
        Identifier wasSelected = selected == null ? null : selected.id();
        this.entries = snapshotEntries();
        if (wasSelected != null) {
            this.selected = entries.stream()
                    .filter(e -> e.id().equals(wasSelected))
                    .findFirst().orElse(null);
        }
        clampPage();
        rebuildModelButtons();
        this.statusMessage = Component.translatable(
                ModLanguageData.Keys.GUI_CHOOSE_MODEL_REFRESH_DONE, stats.models());
    }

    private void onApply(Button btn) {
        if (selected == null) {
            this.onClose();
            return;
        }
        if (this.minecraft != null && this.minecraft.player != null
                && target.distanceToSqr(this.minecraft.player) > SetModelPayload.MAX_INTERACT_DISTANCE_SQR) {
            this.statusMessage = Component.translatable(ModLanguageData.Keys.GUI_CHOOSE_MODEL_TOO_FAR);
            return;
        }
        Services.NETWORK.sendToServer(new SetModelPayload(target.getId(), selected.id()));
        this.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
