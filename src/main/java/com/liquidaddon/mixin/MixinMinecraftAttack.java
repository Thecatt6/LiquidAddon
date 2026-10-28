package com.liquidaddon.mixin;

import com.liquidaddon.modules.ModuleDoubleClicks;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observe the completed vanilla click; the module ignores calls it generated itself. */
@Mixin(Minecraft.class)
public abstract class MixinMinecraftAttack {

    @Inject(method = "startAttack", at = @At("RETURN"))
    private void liquidaddon$afterAttack(CallbackInfoReturnable<Boolean> callback) {
        ModuleDoubleClicks.INSTANCE.onGameClick();
    }
}
