package com.nyaa.infutils.client;

import com.nyaa.infutils.NyaaInfiniteInfernalUtils;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;

@Environment(EnvType.CLIENT)
public class NyaaInfiniteInfernalUtilsClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        NyaaInfiniteInfernalUtils.LOGGER.info("Initializing client-side features...");

        // Render Terraria-style attack curves in world space.
        WorldRenderEvents.AFTER_ENTITIES.register(AttackCurveRenderer::render);

        // Draw the attack-curve debug HUD (only visible when enabled in config).
        HudRenderCallback.EVENT.register(AttackCurveRenderer::renderDebug);

        // Draw the persistent region-name label (centred below bossbar).
        HudRenderCallback.EVENT.register(RegionOverlay::render);

        // Automatic item usage (life / mana potions, piggy bank, soul brush, charge/repair).
        ClientTickEvents.END_CLIENT_TICK.register(AutoUse::tick);

        // Register quick-command hotkeys (X = /spawn, C = /back).
        // Key bindings MUST be registered during mod init, before GameOptions init.
        KeyBindings.register();
        registerQuickCommandKeys();

        NyaaInfiniteInfernalUtils.LOGGER.info("Client-side features initialized!");
    }

    private static void registerQuickCommandKeys() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.currentScreen != null || client.player == null) {
                return;
            }
            if (KeyBindings.spawnKey.wasPressed()) {
                sendCommand(client, "spawn");
            }
            if (KeyBindings.backKey.wasPressed()) {
                sendCommand(client, "back");
            }
            if (KeyBindings.healthPotionKey.wasPressed()) {
                AutoUse.useHealthPotion(client);
            }
            if (KeyBindings.manaPotionKey.wasPressed()) {
                AutoUse.useManaPotion(client);
            }
        });
    }

    private static void sendCommand(MinecraftClient client, String command) {
        client.player.networkHandler.sendChatCommand(command);
    }
}
