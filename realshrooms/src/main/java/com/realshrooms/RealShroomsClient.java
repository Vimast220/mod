package com.realshrooms;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.minecraft.client.render.RenderLayer;

public class RealShroomsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Прозорі пікселі текстур грибів мають бути «вирізані».
        RealShrooms.BLOCKS.values().forEach(block ->
            BlockRenderLayerMap.INSTANCE.putBlock(block, RenderLayer.getCutout()));
    }
}
