package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AgentRole;
import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.agent.tool.ClientToolContext;
import com.dwinovo.animus.task.tasks.BlockMiningProgress;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code inspect_block} tool — read everything the LLM might want to
 * know about a single block before deciding to mine, navigate over, or
 * place against it.
 *
 * <h2>Returned fields</h2>
 * <ul>
 *   <li>{@code block} — registry id (e.g. {@code minecraft:iron_ore})</li>
 *   <li>{@code is_air}, {@code is_solid}, {@code is_liquid}</li>
 *   <li>{@code hardness} — float; -1 means unbreakable (bedrock, barrier)</li>
 *   <li>{@code needs_correct_tool}, {@code current_hand_correct_tool}</li>
 *   <li>{@code estimated_mining_ticks} — with whatever the entity holds now</li>
 *   <li>{@code in_reach} — within 4.5 blocks (the {@code mine_block} reach limit)</li>
 *   <li>{@code distance_to_me}</li>
 * </ul>
 *
 * <p>Useful before {@code mine_block} (confirms in reach + reasonable
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
                + "Returns block id, hardness, whether you have the correct "
                + "tool in hand, an estimated dig-tick count, and whether the "
                + "block is in your 4.5-block mining reach. Call this before "
                + "mine_block to confirm the operation will actually succeed.";
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
    public boolean isLocal() {
        return true;
    }

    @Override
    public Set<AgentRole> allowedRoles() {
        return EnumSet.of(AgentRole.PLAYER, AgentRole.ENTITY);
    }

    @Override
    @SuppressWarnings("deprecation")  // BlockBehaviour.isSolid() carries Mojang's
                                     // "deprecated for override" marker, not phased out.
    public String executeLocal(JsonObject args, ClientToolContext ctx) {
        LivingEntity anchor = ctx.anchor();
        if (anchor == null) {
            return "{\"success\":false,\"message\":\"perspective entity not available\"}";
        }
        int x = readInt(args, "x");
        int y = readInt(args, "y");
        int z = readInt(args, "z");
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = anchor.level().getBlockState(pos);

        JsonObject root = new JsonObject();
        root.addProperty("x", x);
        root.addProperty("y", y);
        root.addProperty("z", z);
        root.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        root.addProperty("is_air", state.isAir());
        root.addProperty("is_solid", state.isSolid());
        root.addProperty("is_liquid", !state.getFluidState().isEmpty());

        float hardness = state.getDestroySpeed(anchor.level(), pos);
        root.addProperty("hardness", hardness);
        root.addProperty("unbreakable", hardness < 0);

        boolean needsTool = state.requiresCorrectToolForDrops();
        root.addProperty("needs_correct_tool", needsTool);
        ItemStack hand = anchor.getMainHandItem();
        boolean correct = hand.isCorrectToolForDrops(state);
        root.addProperty("current_hand_correct_tool", correct);

        if (!state.isAir() && hardness >= 0) {
            float toolSpeed = hand.getDestroySpeed(state);
            if (toolSpeed <= 0.0F) toolSpeed = 1.0F;
            float divisor = correct ? 30.0F : 100.0F;
            int ticks = hardness == 0.0F
                    ? 1
                    : Math.max(1, (int) Math.ceil(hardness * divisor / toolSpeed));
            root.addProperty("estimated_mining_ticks", ticks);
        }

        Vec3 center = Vec3.atCenterOf(pos);
        double distSqr = anchor.distanceToSqr(center);
        root.addProperty("distance_to_me", Math.sqrt(distSqr));
        root.addProperty("in_reach", distSqr <= BlockMiningProgress.REACH_SQR);

        return root.toString();
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
