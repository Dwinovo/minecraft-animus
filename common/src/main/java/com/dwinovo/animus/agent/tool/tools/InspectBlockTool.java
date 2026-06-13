package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.task.tasks.BlockMiningProgress;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code inspect_block} tool — read everything the LLM might want to
 * know about a single block before deciding to mine, navigate over, or
 * place against it.
 *
 * <h2>Returned fields</h2>
 * <ul>
 *   <li>{@code block} — registry id (e.g. {@code minecraft:iron_ore})</li>
 *   <li>{@code properties} — block-state values when present (e.g. an
 *       end_portal_frame's {@code has_eye}/{@code facing}, a stair's facing)</li>
 *   <li>{@code is_air}, {@code is_solid}, {@code is_liquid}</li>
 *   <li>{@code hardness} — float; -1 means unbreakable (bedrock, barrier)</li>
 *   <li>{@code needs_correct_tool}, {@code current_hand_correct_tool}</li>
 *   <li>{@code estimated_mining_ticks} — with whatever the entity holds now</li>
 *   <li>{@code in_reach} — within 4.5 blocks (the {@code auto_mine} reach limit)</li>
 *   <li>{@code distance_to_me}</li>
 * </ul>
 *
 * <p>Useful before {@code auto_mine} (confirms in reach + reasonable
 * dig time) and before {@code pathfind_and_mine} (confirms mineable so
 * the entity doesn't walk to bedrock).
 */
public final class InspectBlockTool implements AnimusTool {

    @Override
    public String name() {
        return "inspect_block";
    }

    @Override
    public String description() {
        return "Inspect a single block at the given integer coordinates. "
                + "Returns block id, its block-state properties when any (e.g. an "
                + "end_portal_frame's has_eye/facing), hardness, whether you have "
                + "the correct tool in hand, an estimated dig-tick count, and "
                + "whether the block is in your 4.5-block mining reach. Call this "
                + "before auto_mine to confirm the operation will succeed, or to "
                + "check which end_portal_frame cells still need an ender_eye.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("x", Map.of("type", "integer", "description", "Block X."));
        properties.put("y", Map.of("type", "integer", "description", "Block Y."));
        properties.put("z", Map.of("type", "integer", "description", "Block Z."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("x", "y", "z"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public long defaultTimeoutTicks() {
        return 1;
    }

    @Override
    public boolean isQuery() {
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")  // BlockBehaviour.isSolid() carries Mojang's
                                     // "deprecated for override" marker, not phased out.
    public String executeQuery(JsonObject args, AnimusPlayer entity) {
        int x = readInt(args, "x");
        int y = readInt(args, "y");
        int z = readInt(args, "z");
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = entity.level().getBlockState(pos);

        JsonObject root = new JsonObject();
        root.addProperty("x", x);
        root.addProperty("y", y);
        root.addProperty("z", z);
        root.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        // Block-state properties (e.g. end_portal_frame's has_eye/facing, so the
        // model can tell which of the 12 frames still need an ender_eye; stairs
        // facing; etc.). Omitted when the block has no properties.
        if (!state.getProperties().isEmpty()) {
            JsonObject props = new JsonObject();
            for (Property<?> p : state.getProperties()) {
                props.addProperty(p.getName(), propValue(state, p));
            }
            root.add("properties", props);
        }
        root.addProperty("is_air", state.isAir());
        root.addProperty("is_solid", state.isSolid());
        root.addProperty("is_liquid", !state.getFluidState().isEmpty());

        float hardness = state.getDestroySpeed(entity.level(), pos);
        root.addProperty("hardness", hardness);
        root.addProperty("unbreakable", hardness < 0);

        boolean needsTool = state.requiresCorrectToolForDrops();
        root.addProperty("needs_correct_tool", needsTool);
        ItemStack hand = entity.getMainHandItem();
        boolean handIsRightTool = hand.isCorrectToolForDrops(state);
        root.addProperty("current_hand_correct_tool", handIsRightTool);

        if (!state.isAir() && hardness >= 0) {
            float toolSpeed = hand.getDestroySpeed(state);
            if (toolSpeed <= 0.0F) toolSpeed = 1.0F;
            // Same vanilla rule as BlockMiningProgress — block that doesn't
            // require correct tool always uses fast divisor.
            boolean fast = !needsTool || handIsRightTool;
            float divisor = fast ? 30.0F : 100.0F;
            int ticks = hardness == 0.0F
                    ? 1
                    : Math.max(1, (int) Math.ceil(hardness * divisor / toolSpeed));
            root.addProperty("estimated_mining_ticks", ticks);
        }

        Vec3 center = Vec3.atCenterOf(pos);
        double distSqr = entity.distanceToSqr(center);
        root.addProperty("distance_to_me", Math.sqrt(distSqr));
        root.addProperty("in_reach", distSqr <= BlockMiningProgress.REACH_SQR);

        return root.toString();
    }

    /** Serialized value of one block-state property (e.g. "true", "north"). */
    private static <T extends Comparable<T>> String propValue(BlockState state, Property<T> p) {
        return p.getName(state.getValue(p));
    }

    private static int readInt(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            throw new IllegalArgumentException("missing required argument: " + key);
        }
        try {
            return args.get(key).getAsInt();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(
                    "argument '" + key + "' must be an integer: " + ex.getMessage());
        }
    }
}
