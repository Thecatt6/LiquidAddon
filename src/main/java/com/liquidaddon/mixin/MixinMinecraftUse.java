package com.liquidaddon.mixin;

import com.liquidaddon.modules.ModuleDoubleClicks;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe the completed vanilla use (right button); the module ignores calls it generated itself. */
@Mixin(Minecraft.class)
public abstract class MixinMinecraftUse {

    @Inject(method = "startUseItem", at = @At("RETURN"))
    private void liquidaddon$afterUse(CallbackInfo callback) {
        ModuleDoubleClicks.INSTANCE.onGameUse();
    }
}
