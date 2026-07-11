package com.nyaa.infutils.util;

import com.nyaa.infutils.NyaaInfiniteInfernalUtils;
import com.nyaa.infutils.config.ModConfig;
import net.minecraft.entity.Entity;

/**
 * Detects marker mobs — any entity whose custom display name starts with the
 * configured prefix (default {@code <黑化>}), ignoring Minecraft color/format
 * codes. Those entities are forced to render with a glow outline.
 */
public final class MonsterGlow {

    private MonsterGlow() {
    }

    /** True if the entity should be given the monster glow. */
    public static boolean isMonster(Entity entity) {
        ModConfig config = NyaaInfiniteInfernalUtils.CONFIG;
        if (config == null || !config.monsterGlow.enabled) {
            return false;
        }
        if (!entity.hasCustomName()) {
            return false;
        }
        // Strip Minecraft §<code> formatting characters before comparing.
        String name = entity.getCustomName().getString().replaceAll("§.", "").trim();
        String prefix = "<黑化>";
        return name.startsWith(prefix);
    }
}
