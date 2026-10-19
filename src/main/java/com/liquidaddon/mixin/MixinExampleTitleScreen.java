package com.liquidaddon.mixin;

import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prova che un add-on può fornire Mixin - la cosa principale che la Script API non poteva mai fare.
 * Mira a una classe vanilla. Mischiare in LiquidBounce stesso funziona anche, ma ha bisogno di una priorità sopra
 * la config del client (1337) e nessuna rimappatura, poiché le sue classi sono già nel target
 * namespace.
 */
@Mixin(TitleScreen.class)
public class MixinExampleTitleScreen {

    @Inject(method = "init", at = @At("RETURN"))
    private void onInit(CallbackInfo info) {
        System.out.println("[LiquidAddon] Title screen initialised");
    }

}
