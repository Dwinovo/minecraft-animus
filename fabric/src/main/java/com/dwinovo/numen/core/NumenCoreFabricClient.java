package com.dwinovo.numen.core;

import com.dwinovo.numen.agent.skill.SkillRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Fabric <em>client</em> entry point for numen-core. The engine (numen-api) ships
 * no skills of its own; core contributes its built-in skills here by declaring
 * the {@code skills/} directory bundled inside this mod's jar. The engine reads
 * it in place on every skill scan — nothing is copied to the player's config, and
 * the player can still override any of these by dropping a same-named directory
 * under {@code config/numen/skills}.
 *
 * <p>Client-only: skills feed the client-side LLM, so this entry point never runs
 * on a dedicated server.
 */
public class NumenCoreFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        FabricLoader.getInstance().getModContainer(Constants.MOD_ID)
                .flatMap(c -> c.findPath("skills"))
                .ifPresentOrElse(
                        root -> SkillRegistry.instance().declareBundled(root),
                        () -> Constants.LOG.warn("[numen-core] no bundled skills/ dir found in jar"));
    }
}
