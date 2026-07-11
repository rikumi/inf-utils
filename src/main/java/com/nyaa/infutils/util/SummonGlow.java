package com.nyaa.infutils.util;

import com.nyaa.infutils.NyaaInfiniteInfernalUtils;
import com.nyaa.infutils.config.ModConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ArmorStandEntity;

/**
 * Helper to detect "summoned creatures" — invisible armor stands with a special
 * appearance — that should be highlighted with a glow effect.
 */
public final class SummonGlow {

    private SummonGlow() {
    }

    /** True if the armor stand has at least one equipped item (hand or armor). */
    public static boolean hasEquipment(ArmorStandEntity armorStand) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (!armorStand.getEquippedStack(slot).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public static boolean isSpecial(Entity entity) {
        if (!(entity instanceof ArmorStandEntity armorStand)) {
            return false;
        }
        // Only invisible armor stands are considered summoned creatures.
        if (!armorStand.isInvisible()) {
            return false;
        }

        ModConfig config = NyaaInfiniteInfernalUtils.CONFIG;
        if (config == null || !config.summonGlow.enabled) {
            return false;
        }

        if (config.summonGlow.requireEquipment) {
            return hasEquipment(armorStand);
        }

        return true;
    }
}
