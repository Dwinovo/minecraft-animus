package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.task.TaskRecord;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;

/**
 * Typed task descriptor for the {@code equip} tool: "take this item out of my
 * inventory and wear/wield it." Completes in a single tick — no pathing.
 *
 * <p>{@link #slot} is {@code null} for auto-routing (armor → its armor slot,
 * weapon/tool → main hand), or a specific slot when the LLM forces one.
 */
public final class EquipTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "equip_item";

    /** The item to equip (must be present in the entity's own inventory). */
    public final Item item;
    /** Target slot, or {@code null} to auto-route by item type. */
    public final EquipmentSlot slot;
    /** Human-readable label for messages / debug overlay (e.g. "wooden_pickaxe"). */
    public final String label;

    public EquipTaskRecord(String toolCallId, long deadlineGameTime,
                           Item item, EquipmentSlot slot, String label) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.item = item;
        this.slot = slot;
        this.label = label;
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + label;
    }
}
