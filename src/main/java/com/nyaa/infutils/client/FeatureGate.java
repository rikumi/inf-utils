package com.nyaa.infutils.client;

import com.nyaa.infutils.NyaaInfiniteInfernalUtils;
import com.nyaa.infutils.config.ModConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.world.GameMode;

/**
 * Central gate for the whole mod. Every feature (auto-use, region overlay,
 * attack-curve rendering, sound replacement, summon/monster glow, and the
 * quick-command hotkeys) is only active while the
 * player is in <b>adventure mode</b>. In any other game mode (survival,
 * creative, spectator) every feature is disabled and its state reset.
 */
public final class FeatureGate {

    private FeatureGate() {
    }

    /** True only when the mod is enabled AND the player is in adventure mode. */
    public static boolean active() {
        ModConfig cfg = NyaaInfiniteInfernalUtils.CONFIG;
        if (cfg == null || !cfg.modEnabled) {
            return false;
        }
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        return player != null && player.getGameMode() == GameMode.ADVENTURE;
    }
}
