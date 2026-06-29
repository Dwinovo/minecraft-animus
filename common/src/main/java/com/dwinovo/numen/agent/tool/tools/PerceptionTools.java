package com.dwinovo.numen.agent.tool.tools;

import com.dwinovo.numen.agent.tool.api.Arg;
import com.dwinovo.numen.agent.tool.api.NumenAction;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.tasks.BlockMiningProgress;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.Vec3;

/**
 * Perception tools authored on the {@link NumenAction} surface — the first
 * built-ins to dogfood the annotation/reflection path. Behaviour is identical
 * to the hand-written {@code NumenTool} classes they replace; only the wiring
 * (auto-derived schema, reflective invoke, entity injected by type) changed.
 */
public final class PerceptionTools {

    @NumenAction(name = "get_self_status", description =
            "Read your complete status in one call: name, game mode, HP / max HP, "
            + "hunger / saturation, position, dimension, biome, the structures you "
            + "are standing in (village, mineshaft, …), equipment (hands + armor — "
            + "an equipped item leaves the backpack, it is NOT lost), your full "
            + "backpack inventory, current attack target, and movement state. ALWAYS "
            + "call this before combat or planning decisions and periodically during "
            + "long tasks. No arguments.")
    public String getSelfStatus(NumenPlayer self) {
        JsonObject root = new JsonObject();
        root.addProperty("entity_id", self.getId());
        root.addProperty("name", self.getName().getString());
        root.addProperty("game_mode", self.gameMode.getGameModeForPlayer().getName());
        root.addProperty("hp", self.getHealth());
        root.addProperty("max_hp", self.getMaxHealth());
        root.addProperty("hunger", self.getFoodData().getFoodLevel());
        root.addProperty("saturation", self.getFoodData().getSaturationLevel());

        JsonObject pos = new JsonObject();
        pos.addProperty("x", self.getX());
        pos.addProperty("y", self.getY());
        pos.addProperty("z", self.getZ());
        root.add("position", pos);

        root.addProperty("dimension", self.level().dimension().location().toString());
        root.addProperty("biome", self.level().getBiome(self.blockPosition())
                .unwrapKey().map(k -> k.location().toString()).orElse("unknown"));
        // Structures whose bounding box contains us right now (e.g. village, mineshaft).
        JsonArray structures = new JsonArray();
        if (self.level() instanceof ServerLevel sl) {
            Registry<Structure> reg = sl.registryAccess().registryOrThrow(Registries.STRUCTURE);
            for (Structure s : sl.structureManager().getAllStructuresAt(self.blockPosition()).keySet()) {
                ResourceLocation key = reg.getKey(s);
                if (key != null) structures.add(key.toString());
            }
        }
        root.add("structures", structures);

        // Equipment: hands + armor. Lives OUTSIDE the backpack container.
        JsonObject equipment = new JsonObject();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack s = self.getItemBySlot(slot);
            if (s.isEmpty()) continue;
            JsonObject o = new JsonObject();
            o.addProperty("item", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
            if (s.getCount() > 1) o.addProperty("count", s.getCount());
            equipment.add(slot.getName(), o);
        }
        root.add("equipment", equipment);

        // Full backpack inventory (empty slots omitted).
        var inv = self.getInventory();
        JsonArray items = new JsonArray();
        int used = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            used++;
            JsonObject o = new JsonObject();
            o.addProperty("slot", i);
            o.addProperty("item", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
            o.addProperty("count", s.getCount());
            items.add(o);
        }
        JsonObject inventory = new JsonObject();
        inventory.add("items", items);
        inventory.addProperty("slots_used", used);
        inventory.addProperty("slots_total", inv.getContainerSize());
        root.add("inventory", inventory);

        // A player body has no AI attack-target; combat is task-driven.
        root.add("target", JsonNull.INSTANCE);

        root.addProperty("on_ground", self.onGround());
        root.addProperty("in_water", self.isInWater());
        root.addProperty("in_lava", self.isInLava());

        return root.toString();
    }

    @NumenAction(name = "inspect_block", description =
            "Inspect a single block at the given integer coordinates. "
            + "Returns block id, its block-state properties when any (e.g. an "
            + "end_portal_frame's has_eye/facing), hardness, whether you have "
            + "the correct tool in hand, an estimated dig-tick count, and "
            + "whether the block is in your 4.5-block mining reach. Call this "
            + "before auto_mine to confirm the operation will succeed, or to "
            + "check which end_portal_frame cells still need an ender_eye.")
    @SuppressWarnings("deprecation")  // BlockBehaviour.isSolid() carries Mojang's
                                     // "deprecated for override" marker, not phased out.
    public String inspectBlock(@Arg("Block X.") int x,
                               @Arg("Block Y.") int y,
                               @Arg("Block Z.") int z,
                               NumenPlayer self) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = self.level().getBlockState(pos);

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

        float hardness = state.getDestroySpeed(self.level(), pos);
        root.addProperty("hardness", hardness);
        root.addProperty("unbreakable", hardness < 0);

        boolean needsTool = state.requiresCorrectToolForDrops();
        root.addProperty("needs_correct_tool", needsTool);
        ItemStack hand = self.getMainHandItem();
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
        double distSqr = self.distanceToSqr(center);
        root.addProperty("distance_to_me", Math.sqrt(distSqr));
        root.addProperty("in_reach", distSqr <= BlockMiningProgress.REACH_SQR);

        return root.toString();
    }

    /** Serialized value of one block-state property (e.g. "true", "north"). */
    private static <T extends Comparable<T>> String propValue(BlockState state, Property<T> p) {
        return p.getName(state.getValue(p));
    }

    @NumenAction(name = "get_owner_status", description =
            "Read your owner's current status: name, online state, HP, "
            + "hunger, position, distance from you, and held item. Call "
            + "before any 'follow', 'protect', or 'rendezvous' decision. "
            + "If the owner is offline the call returns online:false — "
            + "default to autonomous mode until they return. No arguments.")
    public String getOwnerStatus(NumenPlayer self) {
        JsonObject root = new JsonObject();
        java.util.UUID ownerUuid = self.getOwnerUuid();
        if (ownerUuid == null) {
            root.addProperty("online", false);
            root.addProperty("message", "no owner (untamed)");
            return root.toString();
        }
        root.addProperty("owner_uuid", ownerUuid.toString());

        // Server-wide resolution: vanilla getOwner() is scoped to the PET's
        // level and would report a cross-dimension owner as "offline".
        Player player = self.resolveOwnerPlayer();
        if (player == null) {
            root.addProperty("online", false);
            root.addProperty("message", "owner offline");
            return root.toString();
        }

        root.addProperty("online", true);
        root.addProperty("name", player.getName().getString());
        root.addProperty("hp", player.getHealth());
        root.addProperty("max_hp", player.getMaxHealth());
        root.addProperty("hunger", player.getFoodData().getFoodLevel());
        root.addProperty("saturation", player.getFoodData().getSaturationLevel());

        JsonObject pos = new JsonObject();
        pos.addProperty("x", player.getX());
        pos.addProperty("y", player.getY());
        pos.addProperty("z", player.getZ());
        root.add("position", pos);

        boolean sameDimension = self.level().dimension().equals(player.level().dimension());
        root.addProperty("same_dimension", sameDimension);
        root.addProperty("owner_dimension", player.level().dimension().location().toString());
        if (sameDimension) {
            root.addProperty("distance_to_me", self.distanceTo(player));
        } else {
            root.addProperty("note", "owner is in a different dimension — their "
                    + "position is in THAT dimension's coordinates, not yours");
        }
        root.addProperty("main_hand", itemKey(player.getMainHandItem()));
        root.addProperty("off_hand", itemKey(player.getOffhandItem()));

        return root.toString();
    }

    private static String itemKey(ItemStack stack) {
        if (stack.isEmpty()) return "minecraft:air";
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    @NumenAction(name = "get_world_info", description =
            "Read the current world state: dimension, game-time tick counter, "
            + "whether it's bright or dark outside (combat / spawn planning), "
            + "and weather (clear / rain / thunder, affects sailing and combat). "
            + "No arguments.")
    public String getWorldInfo(NumenPlayer self) {
        var level = self.level();

        JsonObject root = new JsonObject();
        root.addProperty("dimension", level.dimension().location().toString());
        root.addProperty("game_time", level.getLevelData().getGameTime());
        root.addProperty("is_bright_outside", level.isDay());
        root.addProperty("is_dark_outside", level.isNight());

        String weather;
        if (level.isThundering()) weather = "thunder";
        else if (level.isRaining()) weather = "rain";
        else weather = "clear";
        root.addProperty("weather", weather);

        return root.toString();
    }
}
