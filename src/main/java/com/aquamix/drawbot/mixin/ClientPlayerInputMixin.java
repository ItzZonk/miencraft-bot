package com.aquamix.drawbot.mixin;

import com.aquamix.drawbot.input.AquamixInput;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerInputMixin extends AbstractClientPlayerEntity {

    @org.spongepowered.asm.mixin.Shadow
    public Input input;

    public ClientPlayerInputMixin(ClientWorld world, GameProfile profile) {
        super(world, profile);
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "<init>", at = @At("RETURN"))
    private void replaceInputOnInit(MinecraftClient client, ClientWorld world,
            net.minecraft.client.network.ClientPlayNetworkHandler networkHandler, net.minecraft.stat.StatHandler stats,
            net.minecraft.client.recipebook.ClientRecipeBook recipeBook, boolean lastSneaking, boolean lastSprinting,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        this.input = new AquamixInput(client.options);
    }
}
