package com.nyaa.infutils.mixin;

import com.nyaa.infutils.client.ManaDisplay;
import com.nyaa.infutils.client.RegionOverlay;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the server-sent big top-center title. The InfiniteInfernal server
 * shows the current region name there; we remember the latest one and display it
 * in the corner (see {@link RegionOverlay}).
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {

    @Inject(method = "onTitle(Lnet/minecraft/network/packet/s2c/play/TitleS2CPacket;)V", at = @At("HEAD"))
    private void infutils_captureRegionTitle(TitleS2CPacket packet, CallbackInfo ci) {
        try {
            net.minecraft.text.Text title = packet.text();
            if (title != null) {
                RegionOverlay.onRegionTitle(title);
            }
        } catch (Throwable ignored) {
            // Defensive: some 1.21.x builds expose the title differently.
        }
    }

    /**
     * Captures the server-sent action-bar (bottom HUD) text, which the
     * InfiniteInfernal server uses to broadcast the player's MANA as "MANA <n>".
     */
    @Inject(method = "onOverlayMessage(Lnet/minecraft/network/packet/s2c/play/OverlayMessageS2CPacket;)V", at = @At("HEAD"), cancellable = true)
    private void infutils_captureMana(OverlayMessageS2CPacket packet, CallbackInfo ci) {
        try {
            Text text = packet.text();
            if (text != null) {
                ManaDisplay.onActionBar(text);
                // When the mana display is enabled and hiding is on, swallow the
                // server's action bar (mana/rage bar) so only our own ★ rendering
                // shows.
                if (ManaDisplay.shouldHideActionBar()) {
                    ci.cancel();
                }
            }
        } catch (Throwable ignored) {
            // Defensive.
        }
    }
}
