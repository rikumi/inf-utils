package com.nyaa.infutils.util;

import com.nyaa.infutils.NyaaInfiniteInfernalUtils;
import com.nyaa.infutils.config.ModConfig;
import net.minecraft.entity.Entity;
import net.minecraft.text.PlainTextContent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects elite mobs — any entity whose custom display name starts with the
 * configured prefix (default {@code <黑化> }) AND whose text <b>immediately</b>
 * after the prefix is colored with vanilla red (§c / §4 — {@link Formatting#RED}
 * 0xFF5555 or {@link Formatting#DARK_RED} 0xAA0000).
 *
 * <p>A red level indicator (e.g. {@code [Lv.5]}) appearing <i>after</i> the
 * non-red name text does NOT count — only the segment directly following the
 * prefix is considered.
 *
 * Elite mobs receive <b>both</b> a vanilla glow outline AND full-bright
 * lighting simultaneously, unlike regular {@link MonsterGlow} which only
 * applies one mode at a time.
 */
public final class EliteGlow {

    private EliteGlow() {}

    private static int logCount = 0;
    private static final int MAX_LOG = 50;

    /** Vanilla red RGB values: §c = RED (0xFF5555), §4 = DARK_RED (0xAA0000) */
    private static final int RED_RGB = 0xFF5555;
    private static final int DARK_RED_RGB = 0xAA0000;

    /** True if the entity should be given the elite glow (both outline + brightness). */
    public static boolean isElite(Entity entity) {
        ModConfig config = NyaaInfiniteInfernalUtils.CONFIG;
        if (config == null || !config.eliteGlow.enabled) {
            return false;
        }
        if (!entity.hasCustomName()) {
            return false;
        }
        String prefix = "<黑化> ";
        Text name = entity.getCustomName();
        String rawGetString = name.getString();
        String plain = rawGetString.replaceAll("§.", "").trim();

        // Debug: log the raw name structure
        if (logCount < MAX_LOG) {
            logCount++;
            NyaaInfiniteInfernalUtils.LOGGER.info(
                    "[infutils][elite] entity={} rawGetString='{}' plain='{}' prefix='{}' prefixLen={}",
                    entity.getName().getString(), rawGetString, plain, prefix, prefix.length());
            logTextTree(name, Style.EMPTY, 0);
        }

        if (!plain.startsWith(prefix)) {
            return false;
        }
        // Check whether the portion after the prefix has vanilla red coloring
        boolean result = hasRedAfterPrefix(name, prefix.length());
        if (logCount < MAX_LOG) {
            NyaaInfiniteInfernalUtils.LOGGER.info("[infutils][elite] hasRedAfterPrefix result={}", result);
        }
        return result;
    }

    /** Log the Text tree structure for debugging. */
    private static void logTextTree(Text text, Style parentStyle, int depth) {
        String indent = "  ".repeat(depth);
        Style ownStyle = text.getStyle();
        TextColor ownColor = ownStyle != null ? ownStyle.getColor() : null;
        String ownColorStr = ownColor != null ? String.format("#%06X", ownColor.getRgb()) : "null";
        TextColor parentColor = parentStyle != null ? parentStyle.getColor() : null;
        String parentColorStr = parentColor != null ? String.format("#%06X", parentColor.getRgb()) : "null";

        String contentStr;
        if (text.getContent() instanceof PlainTextContent literal) {
            contentStr = "PlainTextContent('" + literal.string() + "')";
        } else {
            contentStr = text.getContent().getClass().getSimpleName() + "(" + text.getContent() + ")";
        }

        NyaaInfiniteInfernalUtils.LOGGER.info(
                "{}[depth={}] ownColor={} parentColor={} content={} siblings={}",
                indent, depth, ownColorStr, parentColorStr, contentStr, text.getSiblings().size());

        for (Text sibling : text.getSiblings()) {
            logTextTree(sibling, ownStyle, depth + 1);
        }
    }

    /**
     * Traverse the Text tree and check whether the text <b>immediately</b>
     * after the prefix (the first character at {@code prefixLen}) is colored
     * with vanilla red (§c / §4 only).
     *
     * <p>Only the first segment reaching past the prefix boundary is
     * considered. A red level indicator (e.g. {@code [Lv.5]}) that appears
     * after non-red name text does NOT count.
     */
    private static boolean hasRedAfterPrefix(Text name, int prefixLen) {
        List<StyledSegment> segments = new ArrayList<>();
        collectSegments(name, Style.EMPTY, segments);

        if (logCount < MAX_LOG) {
            for (StyledSegment seg : segments) {
                NyaaInfiniteInfernalUtils.LOGGER.info(
                        "[infutils][elite] segment text='{}' isRed={}",
                        seg.text, seg.isRed);
            }
        }

        int pos = 0;
        for (StyledSegment seg : segments) {
            int segEnd = pos + seg.text.length();
            // First segment that reaches past the prefix boundary:
            // the character right after the prefix must be red.
            if (segEnd > prefixLen) {
                return seg.isRed;
            }
            pos = segEnd;
        }
        return false;
    }

    /**
     * Walk the Text tree depth-first (content → siblings), collecting
     * segments of (plainText, isRed).
     *
     * For {@link PlainTextContent} nodes that contain § formatting codes,
     * those codes are parsed into sub-segments. For structured Text
     * (sibling nodes with their own {@link Style}), the sibling's style
     * is checked directly.
     */
    private static void collectSegments(Text text, Style parentStyle,
                                        List<StyledSegment> segments) {
        Style ownStyle = text.getStyle();
        // Merge: child properties override parent; unset properties inherit parent.
        Style effectiveStyle = ownStyle.withParent(parentStyle);
        boolean effectiveIsRed = isRedStyle(effectiveStyle);

        // --- Process this node's content ---
        if (text.getContent() instanceof PlainTextContent literal) {
            String raw = literal.string();
            if (raw.indexOf('§') >= 0) {
                // § codes present — parse them into sub-segments
                parseSectionCodes(raw, effectiveIsRed, segments);
            } else if (!raw.isEmpty()) {
                segments.add(new StyledSegment(raw, effectiveIsRed));
            }
        } else {
            // Non-literal content (translatable, etc.) — approximate
            String contentText = textContentPlainText(text);
            if (contentText != null && !contentText.isEmpty()) {
                segments.add(new StyledSegment(contentText, effectiveIsRed));
            }
        }

        // --- Process siblings (they inherit this node's effective style) ---
        for (Text sibling : text.getSiblings()) {
            collectSegments(sibling, effectiveStyle, segments);
        }
    }

    /**
     * Parse §-coded formatting in a raw literal string into styled segments.
     * Only §c (RED) and §4 (DARK_RED) are considered red; everything else
     * is not.
     */
    private static void parseSectionCodes(String raw, boolean baseIsRed,
                                          List<StyledSegment> segments) {
        StringBuilder chars = new StringBuilder();
        boolean currentIsRed = baseIsRed;

        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) == '§' && i + 1 < raw.length()) {
                // Flush accumulated characters as a segment
                if (chars.length() > 0) {
                    segments.add(new StyledSegment(chars.toString(), currentIsRed));
                    chars.setLength(0);
                }
                char code = raw.charAt(i + 1);
                Formatting fmt = Formatting.byCode(code);
                if (fmt != null && fmt.isColor()) {
                    currentIsRed = (fmt == Formatting.RED || fmt == Formatting.DARK_RED);
                } else if (code == 'r') {
                    // §r resets all formatting
                    currentIsRed = false;
                }
                i++; // skip the code character
            } else {
                chars.append(raw.charAt(i));
            }
        }
        if (chars.length() > 0) {
            segments.add(new StyledSegment(chars.toString(), currentIsRed));
        }
    }

    /** Check if a {@link Style} has vanilla red (§c 0xFF5555) or dark red (§4 0xAA0000). */
    private static boolean isRedStyle(Style style) {
        if (style == null) return false;
        TextColor color = style.getColor();
        if (color == null) return false;
        int rgb = color.getRgb();
        return rgb == RED_RGB || rgb == DARK_RED_RGB;
    }

    /**
     * Get the plain-text contribution of just this Text node's content
     * (excluding siblings), § codes stripped.
     */
    private static String textContentPlainText(Text text) {
        if (text.getContent() instanceof PlainTextContent literal) {
            return literal.string().replaceAll("§.", "");
        }
        return null;
    }

    /** A run of plain-text characters with a vanilla-red flag. */
    private static class StyledSegment {
        final String text;       // plain text (§ codes stripped), for position tracking
        final boolean isRed;     // exact MC RED (0xFF5555) or DARK_RED (0xAA0000)

        StyledSegment(String text, boolean isRed) {
            this.text = text;
            this.isRed = isRed;
        }
    }
}
