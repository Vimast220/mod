package com.realshrooms;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.fabricmc.fabric.api.registry.CompostingChanceRegistry;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.AliasedBlockItem;
import net.minecraft.item.FoodComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.feature.PlacedFeature;

public class RealShrooms implements ModInitializer {
    public static final String MOD_ID = "realshrooms";

    public static final Map<String, Block> BLOCKS = new LinkedHashMap<>();
    public static final Map<String, Item> ITEMS = new LinkedHashMap<>();

    private record Species(String id, MapColor color, int width, int height, boolean needsTree,
                           FoodComponent food, List<RegistryKey<Biome>> biomes) {
    }

    private static Identifier id(String path) {
        return new Identifier(MOD_ID, path);
    }

    private static FoodComponent.Builder food(int hunger, float saturation) {
        return new FoodComponent.Builder().hunger(hunger).saturationModifier(saturation);
    }

    @Override
    public void onInitialize() {
        List<Species> species = List.of(
            // Лисичка: їстівна, мікоризна, хвойні й листяні ліси.
            new Species("chanterelle", MapColor.ORANGE, 6, 6, true,
                food(3, 0.4f).build(),
                List.of(BiomeKeys.FOREST, BiomeKeys.BIRCH_FOREST, BiomeKeys.OLD_GROWTH_BIRCH_FOREST,
                    BiomeKeys.DARK_FOREST, BiomeKeys.TAIGA, BiomeKeys.OLD_GROWTH_PINE_TAIGA,
                    BiomeKeys.OLD_GROWTH_SPRUCE_TAIGA)),

            // Білий гриб (боровик): їстівний, найситніший.
            new Species("porcini", MapColor.BROWN, 8, 7, true,
                food(4, 0.5f).build(),
                List.of(BiomeKeys.FOREST, BiomeKeys.DARK_FOREST, BiomeKeys.TAIGA,
                    BiomeKeys.OLD_GROWTH_PINE_TAIGA, BiomeKeys.OLD_GROWTH_SPRUCE_TAIGA,
                    BiomeKeys.BIRCH_FOREST)),

            // Зморшок: сирим токсичний, після смаження безпечний. Рідкісний.
            new Species("morel", MapColor.TERRACOTTA_BROWN, 5, 7, true,
                food(1, 0.1f)
                    .statusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 200), 1.0f)
                    .statusEffect(new StatusEffectInstance(StatusEffects.POISON, 100), 1.0f)
                    .alwaysEdible().build(),
                List.of(BiomeKeys.FOREST, BiomeKeys.FLOWER_FOREST, BiomeKeys.BIRCH_FOREST,
                    BiomeKeys.OLD_GROWTH_BIRCH_FOREST)),

            // Мухомор червоний: отруйний.
            new Species("fly_agaric", MapColor.RED, 8, 7, true,
                food(1, 0.0f)
                    .statusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 300), 1.0f)
                    .statusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 600), 1.0f)
                    .alwaysEdible().build(),
                List.of(BiomeKeys.BIRCH_FOREST, BiomeKeys.OLD_GROWTH_BIRCH_FOREST, BiomeKeys.FOREST,
                    BiomeKeys.TAIGA, BiomeKeys.OLD_GROWTH_PINE_TAIGA, BiomeKeys.OLD_GROWTH_SPRUCE_TAIGA)),

            // Бліда поганка: смертельно отруйна.
            new Species("death_cap", MapColor.OLIVE_GREEN, 7, 7, true,
                food(1, 0.0f)
                    .statusEffect(new StatusEffectInstance(StatusEffects.POISON, 600, 1), 1.0f)
                    .statusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 300), 1.0f)
                    .alwaysEdible().build(),
                List.of(BiomeKeys.FOREST, BiomeKeys.DARK_FOREST, BiomeKeys.FLOWER_FOREST,
                    BiomeKeys.BIRCH_FOREST)),

            // Чорнильний гнойовик: з нього роблять чорнило. Любить вологі місця.
            new Species("inky_cap", MapColor.LIGHT_GRAY, 4, 8, false,
                food(2, 0.2f).build(),
                List.of(BiomeKeys.SWAMP, BiomeKeys.MANGROVE_SWAMP, BiomeKeys.DARK_FOREST, BiomeKeys.MEADOW))
        );

        for (Species s : species) {
            AbstractBlock.Settings settings = FabricBlockSettings.create();
            settings = settings
                .mapColor(s.color())
                .noCollision()
                .nonOpaque()
                .breakInstantly()
                .sounds(BlockSoundGroup.GRASS)
                .offset(AbstractBlock.OffsetType.XZ)
                .ticksRandomly()
                .pistonBehavior(PistonBehavior.DESTROY);

            Block block = new ShroomBlock(settings, s.width(), s.height(), s.needsTree());
            Registry.register(Registries.BLOCK, id(s.id()), block);
            BLOCKS.put(s.id(), block);

            // Їжа, яку можна і з'їсти, і посадити (як солодкі ягоди).
            Item item = new AliasedBlockItem(block, new FabricItemSettings().food(s.food()));
            Registry.register(Registries.ITEM, id(s.id()), item);
            ITEMS.put(s.id(), item);

            CompostingChanceRegistry.INSTANCE.add(item, 0.3f);

            // Природна генерація: див. data/realshrooms/worldgen/.
            RegistryKey<PlacedFeature> placed = RegistryKey.of(RegistryKeys.PLACED_FEATURE, id(s.id()));
            BiomeModifications.addFeature(
                BiomeSelectors.includeByKey(s.biomes()),
                GenerationStep.Feature.VEGETAL_DECORATION,
                placed);
        }

        // Смажений зморшок.
        Item cookedMorel = new Item(new FabricItemSettings().food(food(6, 0.6f).build()));
        Registry.register(Registries.ITEM, id("cooked_morel"), cookedMorel);
        ITEMS.put("cooked_morel", cookedMorel);

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.NATURAL).register(entries -> {
            for (Map.Entry<String, Item> e : ITEMS.entrySet()) {
                if (!e.getKey().equals("cooked_morel")) {
                    entries.add(e.getValue());
                }
            }
        });
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FOOD_AND_DRINK).register(entries -> {
            for (Item item : ITEMS.values()) {
                entries.add(item);
            }
        });
    }
}
