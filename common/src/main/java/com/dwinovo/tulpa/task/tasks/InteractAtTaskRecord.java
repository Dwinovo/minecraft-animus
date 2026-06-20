package com.dwinovo.tulpa.task.tasks;

import com.dwinovo.tulpa.task.TaskRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Typed descriptor for {@code interact_at} — the point-aimed half of the native
 * crosshair interaction (the BLOCK and AIR columns of vanilla's
 * {@code startAttack}/{@code startUseItem}; the ENTITY column is {@code interact_entity}).
 *
 * <p>Aim at a world point and press a mouse button; the native raytrace resolves whatever
 * is actually under the aim:
 * <ul>
 *   <li>{@link Button#LEFT} (attack): break the block hit (held until gone); air = nothing.</li>
 *   <li>{@link Button#RIGHT} (use): activate the block hit (lever / door / modded machine), or
 *       — when the aim is clear air — use the held item in that direction (throw an ender
 *       pearl, eat, draw a bow).</li>
 * </ul>
 * {@code aim} null = use the body's CURRENT facing (in-air use with no target, e.g. eating).
 * {@code holdTicks}: 0 = a single press; &gt;0 = hold that many ticks (modded crank / bow draw);
 * -1 = hold until the action self-completes or the task times out.
 */
public final class InteractAtTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "interact_at";

    public enum Button { LEFT, RIGHT }   // left = attack, right = use

    public final Button button;
    public final BlockPos aim;     // null → current facing (in-air use)
    public final int holdTicks;
    public final Item item;        // null → use whatever is already in hand; else equip this first

    public InteractAtTaskRecord(String toolCallId, long deadlineGameTime,
                                Button button, BlockPos aim, int holdTicks, Item item) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.button = button;
        this.aim = aim != null ? aim.immutable() : null;
        this.holdTicks = holdTicks;
        this.item = item;
    }

    /**
     * Why {@code item} must not be used through the fake-player body, or {@code null} if it's fine.
     * The companion is a WORLD actuator: a consumable would feed the fake player (no heal, wasted),
     * and an ender pearl's landing teleports its OWNER — body-bound and undefined for a fake player.
     */
    public static String bodyBoundReason(Item item) {
        if (item == null) {
            return null;
        }
        if (item.components().has(DataComponents.FOOD)) {
            return BuiltInRegistries.ITEM.getKey(item).getPath()
                    + " is a consumable — use eat_item (using it through the world body wouldn't heal you).";
        }
        if (item == Items.ENDER_PEARL) {
            return "ender_pearl teleportation is body-bound and not supported — to travel use move_to, "
                    + "to find a stronghold use locate_structure(\"minecraft:stronghold\").";
        }
        return null;
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + (button == Button.LEFT ? "left" : "right")
                + (item != null ? " " + BuiltInRegistries.ITEM.getKey(item).getPath() : "")
                + (aim != null ? " @" + aim.getX() + "," + aim.getY() + "," + aim.getZ() : " (forward)")
                + (holdTicks != 0 ? " hold=" + holdTicks : "");
    }
}
