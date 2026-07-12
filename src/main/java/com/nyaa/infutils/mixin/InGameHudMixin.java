package com.nyaa.infutils.mixin;

import com.nyaa.infutils.client.ManaDisplay;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the mana stars just BEFORE the vanilla held-item name tooltip
 * ({@code InGameHud.renderHeldItemTooltip}) is painted. The Fabric
 * {@code HudRenderCallback} fires AFTER that name is drawn, so rendering mana
 * there would paint the stars on top of the hotbar item-switch text tooltip.
 * Injecting at the head of {@code renderHeldItemTooltip} puts the stars
 * underneath that text instead.
 * <p>
 * Note: in 1.21.11 the former {@code renderSelectedItemName} was renamed to
 * the private {@code renderHeldItemTooltip}.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {

    @Inject(method = "renderHeldItemTooltip", at = @At("HEAD"))
    private void infutils_renderManaBelowItemName(DrawContext context, CallbackInfo ci) {
        try {
            ManaDisplay.render(context, MinecraftClient.getInstance().getRenderTickCounter());
        } catch (Throwable ignored) {
            // Defensive.
        }
    }
}
