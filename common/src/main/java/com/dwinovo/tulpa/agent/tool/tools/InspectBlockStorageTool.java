package com.dwinovo.tulpa.agent.tool.tools;

import com.dwinovo.tulpa.agent.tool.TulpaTool;
import com.dwinovo.tulpa.entity.TulpaPlayer;
import com.dwinovo.tulpa.platform.Services;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code inspect_block_storage} — read what a block <em>holds</em> (items, fluid, energy)
 * straight from the block, WITHOUT opening its GUI.
 *
 * <p>This is the capability-based "eyes": most modded machines, tanks and batteries expose
 * their contents through the loader's standard item/fluid/energy handlers (the same contract
 * pipes and hoppers use), so one generic read perceives the majority of them with no per-mod
 * code and no GUI round-trip. The loader-specific reading lives behind
 * {@link com.dwinovo.tulpa.platform.services.IBlockCapabilityReader}; this tool just frames it.
 *
 * <p>Complements {@code inspect_block} (block id / mineability) and {@code inspect_gui} (an
 * already-open menu). Prefer this when you only need a machine's contents/levels.
 */
public final class InspectBlockStorageTool implements TulpaTool {

    @Override
    public String name() {
        return "inspect_block_storage";
    }

    @Override
    public String description() {
        return "Read what a block HOLDS — items, fluid, and energy — directly from the block, "
                + "WITHOUT opening its GUI. Works on most modded machines, tanks and batteries "
                + "(chests, furnaces, Create / Mekanism / Thermal machines, fluid tanks, energy "
                + "cells) because they expose standard item/fluid/energy handlers. Give the block's "
                + "integer x/y/z. Use this instead of right-click + inspect_gui when you just need a "
                + "machine's contents or fill levels. Note: storage-network terminals (AE2/RS) show "
                + "only their local buffer here, not the whole network.";
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
    public String executeQuery(JsonObject args, TulpaPlayer entity) {
        int x = readInt(args, "x");
        int y = readInt(args, "y");
        int z = readInt(args, "z");
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = entity.level().getBlockState(pos);
        String coord = x + "," + y + "," + z;
        if (state.isAir()) {
            return "block at " + coord + " is air — nothing to read.";
        }
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        String caps = Services.CAPS.describe(entity.level(), pos);
        if (caps == null || caps.isBlank()) {
            return id + " at " + coord + " exposes no item/fluid/energy storage "
                    + "(not a machine/tank/battery, or it keeps its state elsewhere). "
                    + "If it has a GUI, right-click it then use inspect_gui.";
        }
        return id + " at " + coord + ":\n" + caps;
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
