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

        // Draw the mana stars above the hunger bar. Registered via InGameHudMixin
        // (head of renderSelectedItemName) so the stars sit BELOW the hotbar
        // item-switch text tooltip instead of covering it.

        // Draw the sound-replace debug overlay (only visible when enabled in config).
        HudRenderCallback.EVENT.register(SoundDebug::render);

        // Drop-sound: play XP / level-up SFX when mineral blocks are obtained.
        ClientTickEvents.END_CLIENT_TICK.register(DropSound::tick);

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
        });
    }

    private static void sendCommand(MinecraftClient client, String command) {
        client.player.networkHandler.sendChatCommand(command);
    }
}
