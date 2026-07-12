package com.nyaa.infutils.client;

import com.nyaa.infutils.NyaaInfiniteInfernalUtils;
import com.nyaa.infutils.config.ModConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.resource.Resource;
import net.minecraft.text.PlainTextContent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mana display. The InfiniteInfernal server broadcasts the player's mana in the
 * bottom action bar as something like {@code "MANA 123 ▲▲▲"}. We parse that
 * text (including the <i>colour</i> of the arrow icons after the number) to
 * maintain both the <b>current</b> and <b>maximum</b> mana, then draw them
 * as filled five-pointed stars (★) above the hunger bar.
 * <p>
 * The arrow icons after the number encode the mana level. Two rules keep the
 * current / max split unambiguous:
 * <ol>
 *   <li>When every arrow icon shares the <b>same colour</b> and the number is
 *       non-zero, the player is at full capacity — the number is the <b>max</b>
 *       mana (updated unconditionally, even downward, e.g. when armour changes
 *       and shrinks the cap). Current mana equals max at that point.</li>
 *   <li>When the number is below the max and rule 1 does not hold, the number
 *       is the <b>current</b> mana (not the max).</li>
 * </ol>
 * Display: 10 mana = 1 star, 10 stars per line; the bottom line is filled
 * first, then rows stack upward.
 */
public final class ManaDisplay {

    private ManaDisplay() {
    }

    /** Matches the mana number in the action-bar text, e.g. "MANA 123". */
    private static final Pattern MANA_PATTERN = Pattern.compile("MANA\\s+(\\d+)");

    /** Colour of empty (unfilled capacity) stars: dark black-purple. */
    private static final int EMPTY_STAR_RGB = 0x2A0A3D;
    /** Tint (ABGR) for empty (unfilled) icons: dimmed purple of {@link #EMPTY_STAR_RGB}. */
    private static final int EMPTY_TINT = 0xFF3D0A2A;

    /** Mana icon (8x8 PNG from resources, converted 1:1 from the original 1.bmp). */
    private static final Identifier MANA_ICON = Identifier.of("nyaa-infinite-infernal-utils", "textures/mana.png");
    private static boolean manaIconTried = false;
    private static boolean manaIconOk = false;

    /** Latest parsed max mana (-1 = unknown). Updated by rule 1 only. */
    private static int maxMana = -1;
    /** Latest parsed current mana (-1 = unknown). Updated by rule 2. */
    private static int currentMana = -1;

    /** Called from the action-bar (overlay message) mixin with the styled Text. */
    public static void onActionBar(Text text) {
        if (text == null) {
            return;
        }
        // Flatten the styled Text into (plainText, colourRgb) segments. The plain
        // text equals text.getString(); the colours let us inspect the arrow icons.
        List<StyledSegment> segments = new ArrayList<>();
        collectSegments(text, Style.EMPTY, segments);
        StringBuilder plain = new StringBuilder();
        int total = 0;
        for (StyledSegment seg : segments) {
            total += seg.text.length();
        }
        int[] colors = new int[total];
        int idx = 0;
        for (StyledSegment seg : segments) {
            plain.append(seg.text);
            for (int k = 0; k < seg.text.length(); k++) {
                colors[idx++] = seg.colorRgb;
            }
        }
        String full = plain.toString();

        Matcher m = MANA_PATTERN.matcher(full);
        if (!m.find()) {
            return;
        }
        int num;
        try {
            num = Integer.parseInt(m.group(1));
        } catch (NumberFormatException ignored) {
            return;
        }
        int end = m.end(); // index just past the digits

        // The arrow-icon region is everything after the number (skip any space).
        int arrowStart = end;
        while (arrowStart < full.length() && Character.isWhitespace(full.charAt(arrowStart))) {
            arrowStart++;
        }
        boolean hasArrow = false;
        boolean allSameColor = true;
        Integer arrowColor = null;
        for (int i = arrowStart; i < full.length(); i++) {
            char c = full.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            hasArrow = true;
            int col = colors[i];
            if (col < 0) {            // an uncoloured arrow char -> not uniform
                allSameColor = false;
                break;
            }
            if (arrowColor == null) {
                arrowColor = col;
            } else if (arrowColor.intValue() != col) {
                allSameColor = false;
                break;
            }
        }

        // First reading: we cannot yet tell current from max by the rules, so seed
        // both with this number. Rule 1 corrects max (and current) later whenever
        // the player is seen at full capacity.
        if (maxMana < 0) {
            maxMana = num;
            currentMana = num;
            return;
        }

        if (hasArrow && allSameColor && arrowColor != null && num != 0 && num % 5 == 0) {
            // Rule 1: at full capacity -> number is the MAX (update always, even
            // downward e.g. when armour shrinks the cap), and current equals max.
            maxMana = num;
            currentMana = num;
        } else if (num < maxMana) {
            // Rule 2: below capacity -> number is the CURRENT mana.
            currentMana = num;
        }
        // Otherwise neither rule applies: leave both values unchanged.
    }

    /** Clears the remembered mana (e.g. on disconnect / world switch). */
    public static void reset() {
        maxMana = -1;
        currentMana = -1;
    }

    /**
     * @return true when the mana-display feature is on and wants the server's
     *         action bar (mana/rage bar) suppressed, so only our own ★ rendering
     *         is visible.
     */
    public static boolean shouldHideActionBar() {
        ModConfig cfg = NyaaInfiniteInfernalUtils.CONFIG;
        if (cfg == null || !cfg.manaDisplay.enabled) {
            return false;
        }
        return cfg.manaDisplay.hideActionBar;
    }

    /** Called from HudRenderCallback to draw the mana stars. */
    public static void render(DrawContext graphics, RenderTickCounter tickCounter) {
        if (!FeatureGate.active()) {
            return;
        }
        ModConfig cfg = NyaaInfiniteInfernalUtils.CONFIG;
        if (cfg == null || !cfg.manaDisplay.enabled) {
            return;
        }
        if (maxMana <= 0) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.textRenderer == null || client.getWindow() == null) {
            return;
        }

        int perLine = 10;
        int capacitySlots = (maxMana + perLine - 1) / perLine; // capacity in stars (ceil)
        if (capacitySlots <= 0) {
            return;
        }
        // Filled stars for the current mana. At full capacity (currentMana >= maxMana,
        // i.e. rule 1 fired) every slot must be filled even when maxMana is NOT a
        // multiple of 10 — otherwise the last star would wrongly render empty and the
        // bar would look "not full" (one star short). Otherwise floor to whole decades.
        int filled;
        if (currentMana >= maxMana) {
            filled = capacitySlots;
        } else {
            filled = currentMana / 10;
        }
        if (filled < 0) {
            filled = 0;
        }
        if (filled > capacitySlots) {
            filled = capacitySlots; // safety clamp
        }

        int displaySlots = capacitySlots;
        if (displaySlots <= 0) {
            return;
        }

        ensureManaIcon(client);

        int drawSize = 8;             // PNG size and in-game icon size (user-provided 8x8)
        int texSize = 8;              // mana.png is 8x8, 1:1 with draw size
        int icon = drawSize;
        int cellW = drawSize;          // icon cell width (one per 10 mana)
        int lineH = drawSize + 2;          // row height
        int totalRows = (displaySlots + perLine - 1) / perLine;

        int screenW = client.getWindow().getScaledWidth();
        int screenH = client.getWindow().getScaledHeight();
        // Above the food (hunger) bar, which sits at the bottom-centre, right of health.
        int barTop = screenH - 39;                  // top of the status bars
        int bottomRowTopY = barTop - 2 - icon;    // top y of the bottom icon row
        int centerX = screenW / 2 + 50;            // centre of the food bar
        // Left edge shared by all rows (rows are left-aligned). The whole block is
        // centred on the food bar using a full 10-icon row as reference width.
        int blockW = perLine * cellW;
        int leftX = centerX - blockW / 2 + cfg.manaDisplay.xOffset;

        // Tint (ABGR) applied per icon. Filled = white (no change); empty = a
        // dimmed purple version of the same icon. Alpha comes from textAlpha.
        int a = cfg.manaDisplay.textAlpha & 0xFF;
        int filledTint = (a << 24) | 0x00FFFFFF;
        int emptyTint = (a << 24) | (EMPTY_TINT & 0x00FFFFFF);

        // Bottom row (r = 0) is filled first, then rows stack upward; every row is
        // left-aligned. Filled = full-brightness icon, empty = dimmed icon.
        for (int r = 0; r < totalRows; r++) {
            int rowTopY = bottomRowTopY - r * lineH + cfg.manaDisplay.yOffset;
            int slotsInRow = Math.min(perLine, displaySlots - r * perLine);
            for (int c = 0; c < slotsInRow; c++) {
                int s = r * perLine + c;             // global slot index, 0 = bottom-left
                int x = leftX + c * cellW;
                if (manaIconOk) {
                    int tint = (s < filled) ? filledTint : emptyTint;
                    graphics.drawTexture(RenderPipelines.GUI_TEXTURED, MANA_ICON,
                            x, rowTopY, 0f, 0f, drawSize, drawSize, texSize, texSize, tint);
                } else {
                    // Fallback to text stars if the icon failed to load.
                    int color = (s < filled)
                            ? (0xFF << 24) | (cfg.manaDisplay.starColor & 0x00FFFFFF)
                            : (0xFF << 24) | (EMPTY_STAR_RGB & 0x00FFFFFF);
                    graphics.drawText(client.textRenderer, Text.literal("★"), x, rowTopY, color, cfg.manaDisplay.shadow);
                }
            }
        }
    }

    // ---- mana icon: load the 8x8 PNG from resources (green 00FF00 = transparent) ----

    /** Loads the mana icon PNG from resources and uploads it as a GL texture (once). */
    private static void ensureManaIcon(MinecraftClient client) {
        if (manaIconTried) {
            return;
        }
        manaIconTried = true;
        try {
            Optional<Resource> opt = client.getResourceManager().getResource(MANA_ICON);
            if (opt.isPresent()) {
                try (InputStream in = opt.get().getInputStream()) {
                    NativeImage img = NativeImage.read(in);
                    client.getTextureManager().registerTexture(MANA_ICON,
                            new NativeImageBackedTexture(() -> "mana-icon", img));
                    manaIconOk = true;
                }
            }
        } catch (Throwable t) {
            manaIconOk = false;
        }
    }

    // ---- styled-Text walking (adapted from EliteGlow.collectSegments) ----

    /** A run of plain text with its effective colour (rgb, or -1 if none). */
    private static final class StyledSegment {
        final String text;
        final int colorRgb;

        StyledSegment(String text, int colorRgb) {
            this.text = text;
            this.colorRgb = colorRgb;
        }
    }

    /** Walk the Text tree depth-first, collecting (plainText, colourRgb) segments. */
    private static void collectSegments(Text text, Style parentStyle, List<StyledSegment> segments) {
        Style own = text.getStyle();
        Style effective = own.withParent(parentStyle);
        int rgb = colorRgbOf(effective);

        if (text.getContent() instanceof PlainTextContent literal) {
            String raw = literal.string();
            if (raw.indexOf('§') >= 0) {
                parseSectionCodes(raw, rgb, segments);
            } else if (!raw.isEmpty()) {
                segments.add(new StyledSegment(raw, rgb));
            }
        } else {
            String contentText = textContentPlainText(text);
            if (contentText != null && !contentText.isEmpty()) {
                segments.add(new StyledSegment(contentText, rgb));
            }
        }

        for (Text sibling : text.getSiblings()) {
            collectSegments(sibling, effective, segments);
        }
    }

    /** Parse §-coded formatting in a raw literal into coloured segments. */
    private static void parseSectionCodes(String raw, int baseRgb, List<StyledSegment> segments) {
        StringBuilder chars = new StringBuilder();
        int currentRgb = baseRgb;
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) == '§' && i + 1 < raw.length()) {
                if (chars.length() > 0) {
                    segments.add(new StyledSegment(chars.toString(), currentRgb));
                    chars.setLength(0);
                }
                char code = raw.charAt(i + 1);
                Formatting fmt = Formatting.byCode(code);
                if (fmt != null && fmt.isColor()) {
                    Integer v = fmt.getColorValue();
                    currentRgb = (v != null) ? v : -1;
                } else if (code == 'r') {
                    currentRgb = -1; // reset all formatting
                }
                i++; // skip the code character
            } else {
                chars.append(raw.charAt(i));
            }
        }
        if (chars.length() > 0) {
            segments.add(new StyledSegment(chars.toString(), currentRgb));
        }
    }

    /** Effective colour of a Style as rgb, or -1 if none is set. */
    private static int colorRgbOf(Style style) {
        if (style == null) {
            return -1;
        }
        net.minecraft.text.TextColor color = style.getColor();
        return color != null ? color.getRgb() : -1;
    }

    /** Plain-text contribution of a non-literal Text node (§ codes stripped). */
    private static String textContentPlainText(Text text) {
        if (text.getContent() instanceof PlainTextContent literal) {
            return literal.string().replaceAll("§.", "");
        }
        return null;
    }
}
