package com.dwinovo.numen.core;

import com.dwinovo.numen.agent.skill.SkillRegistry;
import com.dwinovo.numen.core.pathing.cache.PathCaches;
import com.dwinovo.numen.core.task.CompanionTickDispatcher;
import com.dwinovo.numen.core.task.ScanBlocksJob;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

import java.nio.file.Path;

/**
 * Forge entry point for the numen-core tool pack. Registers the tools and task
 * runners into the numen-api engine, then wires the server-tick work its tools
 * need (budget-sliced block scans, the off-thread pathfinder's chunk snapshots).
 * The engine itself is brought up by the separate numen-api mod, which core
 * depends on.
 *
 * <p>Forge keeps separate mod and game event buses, just like the NeoForge
 * reference this was ported from — per-tick / world lifecycle events go on
 * {@link MinecraftForge#EVENT_BUS}.
 */
@Mod(Constants.MOD_ID)
public class NumenCoreForge {

    public NumenCoreForge() {
        NumenCore.init();

        MinecraftForge.EVENT_BUS.addListener(NumenCoreForge::onServerTickPost);
        // Release pathfinding chunk-ref snapshots when the server stops (don't pin an old world).
        MinecraftForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> PathCaches.dropAll());

        // Client-only: declare core's built-in skills, read in place from the
        // skills/ dir bundled in this jar. Skills feed the client-side LLM, so
        // this never runs on a dedicated server.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            declareBundledSkills();
        }

        Constants.LOG.info("numen-core initialised on Forge.");
    }

    private static void declareBundledSkills() {
        Path root = ModList.get().getModFileById(Constants.MOD_ID).getFile().findResource("skills");
        if (root != null) {
            SkillRegistry.instance().declareBundled(root);
        } else {
            Constants.LOG.warn("[numen-core] no bundled skills/ dir found in jar");
        }
    }

    private static void onServerTickPost(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        CompanionTickDispatcher.tick(server);
        ScanBlocksJob.tick(server);
        PathCaches.serverTick(server);
    }
}
