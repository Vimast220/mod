package com.realshrooms;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry;
import net.minecraft.block.Block;
import net.minecraft.client.color.world.BiomeColors;
import net.minecraft.client.color.world.GrassColors;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

public class RealShroomsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Прозорі пікселі текстур грибів мають бути «вирізані».
        RealShrooms.BLOCKS.values().forEach(block ->
            BlockRenderLayerMap.INSTANCE.putBlock(block, RenderLayer.getCutout()));
        // Блоки грибниці: захоплений блок і напівпрозорі нитки поверх нього.
        MyceliumBlock.ALL.forEach(block ->
            BlockRenderLayerMap.INSTANCE.putBlock(block, RenderLayer.getCutout()));

        // Блок «трава» під грибницею лишається зеленим: верх і узлісся тонуються кольором трави біому.
        ColorProviderRegistry.BLOCK.register((state, world, pos, tintIndex) -> {
            if (tintIndex == 0 && state.get(MyceliumBlock.HOST) == Host.GRASS) {
                return world != null && pos != null ? BiomeColors.getGrassColor(world, pos) : GrassColors.getColor(0.5, 1.0);
            }
            return 0xFFFFFF;
        }, MyceliumBlock.ALL.toArray(new Block[0]));

        // Підказки про вирощування (tooltip.realshrooms.<id>.1 ... .4).
        ItemTooltipCallback.EVENT.register((stack, context, lines) -> {
            Identifier key = Registries.ITEM.getId(stack.getItem());
            if (!key.getNamespace().equals(RealShrooms.MOD_ID)) {
                return;
            }
            String base = "tooltip." + key.getNamespace() + "." + key.getPath();
            for (int i = 1; i <= 4; i++) {
                String line = base + "." + i;
                if (I18n.hasTranslation(line)) {
                    lines.add(Text.translatable(line).formatted(Formatting.GRAY));
                }
            }
        });
    }
}
