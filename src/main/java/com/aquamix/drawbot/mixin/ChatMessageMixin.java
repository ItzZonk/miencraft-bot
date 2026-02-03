package com.aquamix.drawbot.mixin;

import com.aquamix.drawbot.AquamixDrawBot;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin для перехвата сообщений чата
 * Используется для обнаружения подтверждения удаления чанка
 */
@Mixin(ClientPlayNetworkHandler.class)
public class ChatMessageMixin {
    
    @Inject(method = "onGameMessage", at = @At("HEAD"))
    private void onGameMessage(GameMessageS2CPacket packet, CallbackInfo ci) {
        try {
            String message = packet.content().getString();
            AquamixDrawBot.INSTANCE.getBotController().onChatMessage(message);
        } catch (Exception e) {
            // Ignore errors to not crash the game
        }
    }
}

