package com.nyaa.infutils.mixin;

import com.nyaa.infutils.NyaaInfiniteInfernalUtils;
import com.nyaa.infutils.client.FeatureGate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Disables the wither health-bar darkening.
 *
 * <p>In 1.21 the health-bar hearts are drawn via {@code InGameHud.renderHealthBar},
 * which picks the heart texture from {@code InGameHud$HeartType.fromPlayerState(player)}.
 * When the player has the wither effect that method returns {@code WITHERED}, whose
 * textures are the dark/black hearts — making the health bar hard to read. We override
 * the return value to {@code NORMAL} (the red hearts) while the config option is on,
 * leaving the actual wither gameplay effect untouched.
 *
 * <p>The target enum is package-private, so we avoid referencing its type directly and
 * match by enum name instead.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.InGameHud$HeartType")
public abstract class HeartTypeMixin {

    @Inject(method = "fromPlayerState", at = @At("RETURN"), cancellable = true)
    private static void infutils_disableWitherHealthDarken(CallbackInfoReturnable cir) {
        if (!FeatureGate.active()) {
            return;
        }
        Object ret = cir.getReturnValue();
        if (!(ret instanceof Enum<?> e) || !"WITHERED".equals(e.name())) {
            return;
        }
        if (NyaaInfiniteInfernalUtils.CONFIG == null
                || !NyaaInfiniteInfernalUtils.CONFIG.disableWitherHealthDarken) {
            return;
        }
        // Replace the dark "withered" hearts with the normal red ones.
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class type = (Class) ret.getClass();
        cir.setReturnValue(Enum.valueOf(type, "NORMAL"));
    }
}
