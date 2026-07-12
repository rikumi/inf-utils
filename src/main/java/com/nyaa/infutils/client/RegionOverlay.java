package com.nyaa.infutils.client;

import com.nyaa.infutils.NyaaInfiniteInfernalUtils;
import com.nyaa.infutils.config.ModConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;

/**
 * Remembers the latest region name the server announced (via the big top-center
 * title) and draws it persistently on-screen. The label is centred horizontally
 * just below the boss-bar area.
 */
public final class RegionOverlay {

    /** Spawn region, shown as a fallback before the server announces a region. */
    private static final String DEFAULT_REGION = "月耀城";

    /** Latest non-empty region title received from the server (null until first one). */
    private static volatile Text latestRegion = null;
    private static volatile String latestString = "";

    private RegionOverlay() {
    }

    /** Called from the network-handler mixin whenever the server sends a title. */
    public static void onRegionTitle(Text title) {
        String s = title.getString();
        if (s == null || s.isBlank()) {
            return;
        }
        latestRegion = title;
        latestString = s;
        if (NyaaInfiniteInfernalUtils.CONFIG != null
                && NyaaInfiniteInfernalUtils.CONFIG.regionOverlay.debugLog) {
            NyaaInfiniteInfernalUtils.LOGGER.info("[infutils][region] now in region: {}", s);
        }
    }

    /** Clears the remembered region (e.g. on disconnect). */
    public static void reset() {
        latestRegion = null;
        latestString = "";
    }

    /**
     * Returns the region name to display: the last server title, or the spawn
     * region (月耀城) as a fallback while in adventure mode before the server
     * has announced a region (it may not send a title right after joining).
     */
    public static String currentRegion() {
        if (!latestString.isEmpty()) {
            return latestString;
        }
        if (FeatureGate.active()) {
            return DEFAULT_REGION;
        }
        return "";
    }

    /** Called from HudRenderCallback to draw the region label. */
    public static void render(DrawContext graphics, RenderTickCounter tickCounter) {
        if (!FeatureGate.active()) {
            return;
        }
        ModConfig config = NyaaInfiniteInfernalUtils.CONFIG;
        if (config == null || !config.regionOverlay.enabled) {
            return;
        }
        String regionText = currentRegion();
        if (regionText.isEmpty()) {
            return;
        }
        // Use the server-styled title when available; otherwise the plain fallback.
        Text display = latestRegion != null ? latestRegion : Text.literal(regionText);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.textRenderer == null) {
            return;
        }

        int color = config.regionOverlay.textColor;
        int alpha = config.regionOverlay.textAlpha & 0xFF;
        int argb = (color & 0x00FFFFFF) | (alpha << 24);

        // Centre horizontally on the screen, offset by config xOffset.
        int screenWidth = client.getWindow().getScaledWidth();
        int textWidth = client.textRenderer.getWidth(display);
        int x = (screenWidth - textWidth) / 2 + config.regionOverlay.xOffset;

        // Place below the boss-bar area. Vanilla renders bars starting at y=12,
        // each bar ~19 px tall. With 1 bar the bottom is ~31; we add a small gap.
        int y = config.regionOverlay.yOffset;

        graphics.drawText(client.textRenderer, display, x, y, argb,
                config.regionOverlay.shadow);
    }
}
