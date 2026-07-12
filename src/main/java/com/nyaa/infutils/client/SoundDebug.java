package com.nyaa.infutils.client;

import com.nyaa.infutils.NyaaInfiniteInfernalUtils;
import com.nyaa.infutils.config.ModConfig;
import com.nyaa.infutils.sound.SoundReplacer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * On-screen debug overlay for the sound-replace feature. When
 * {@code soundReplace.debugOverlay} is enabled, every server sound captured by
 * {@code ClientWorldMixin} is listed with its id, category, registered status,
 * whether it was an Inf-sound candidate, and whether it was actually replaced.
 * This makes it trivial to see why a weapon's sound (e.g. a summon weapon) is
 * or isn't being swapped for a Terraria SFX.
 */
public final class SoundDebug {

    private static final int MAX_ENTRIES = 16;
    private static final Deque<Entry> recent = new ArrayDeque<>();

    private static final class Entry {
        String id;
        String category;
        boolean registered;
        boolean candidate;
        boolean replaced;
        String replacement;
        String weaponCtx;
    }

    private SoundDebug() {
    }

    private static boolean isEnabled() {
        ModConfig cfg = NyaaInfiniteInfernalUtils.CONFIG;
        return cfg != null && cfg.soundReplace.debugOverlay && FeatureGate.active();
    }

    /** Called from {@code ClientWorldMixin.handleSound} for every captured sound. */
    public static void record(String id, String category, boolean registered,
                              boolean candidate, boolean replaced,
                              String replacement, String weaponCtx) {
        if (!isEnabled()) {
            return;
        }
        Entry e = new Entry();
        e.id = id;
        e.category = category;
        e.registered = registered;
        e.candidate = candidate;
        e.replaced = replaced;
        e.replacement = replacement;
        e.weaponCtx = weaponCtx;
        recent.addLast(e);
        while (recent.size() > MAX_ENTRIES) {
            recent.removeFirst();
        }
    }

    /** Called from HudRenderCallback to draw the debug list. */
    public static void render(DrawContext graphics, RenderTickCounter tickCounter) {
        if (!isEnabled()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.textRenderer == null) {
            return;
        }

        int x = 4;
        int y = 4;
        int lineH = 10;

        graphics.drawText(client.textRenderer,
                Text.literal("[infutils] 最近捕获的声音 (debug)"),
                x, y, 0xFFFFFF00, true);
        y += lineH + 2;

        graphics.drawText(client.textRenderer,
                Text.literal("ctx: " + SoundReplacer.debugWeaponInfo()),
                x, y, 0xFFAAAAAA, true);
        y += lineH + 2;

        for (Entry e : recent) {
            String reg = e.registered ? "reg" : "UNREG";
            String status = e.replaced
                    ? ("-> " + shortId(e.replacement))
                    : (e.candidate ? "cand(no-repl)" : "skip");
            int color = e.replaced ? 0xFF55FF55 : (e.candidate ? 0xFFFFFF55 : 0xFFFF5555);
            String line = String.format("%s [%s] %s | %s",
                    reg, e.category == null ? "?" : e.category, shortId(e.id), status);
            graphics.drawText(client.textRenderer, Text.literal(line), x, y, color, true);
            y += lineH;
        }
    }

    private static String shortId(String id) {
        if (id == null) {
            return "null";
        }
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }
}
