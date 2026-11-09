package com.realshrooms;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
import net.minecraft.item.BlockItem;
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
    public static Block SUBSTRATE;

    private record Species(String id, MapColor color, int width, int height, boolean needsTree,
                           FoodComponent food, List<RegistryKey<Biome>> biomes,
                           int glow, boolean onWood, boolean humid, boolean onStone, boolean needsDark) {
        // Звичайний гриб: не світиться, росте на ґрунті, волога й темрява не обов'язкові.
        Species(String id, MapColor color, int width, int height, boolean needsTree,
                FoodComponent food, List<RegistryKey<Biome>> biomes) {
            this(id, color, width, height, needsTree, food, biomes, 0, false, false, false, false);
        }
    }

    private static Identifier id(String path) {
        return new Identifier(MOD_ID, path);
    }

    private static FoodComponent.Builder food(int hunger, float saturation) {
        return new FoodComponent.Builder().hunger(hunger).saturationModifier(saturation);
    }

    /** Реєструє три частини грибниці виду: головну (з предметом), вузол і гіфи. */
    private static void registerMycelium(String species, Identifier mushroom, MapColor color,
                                         MyceliumBlock.Diet diet, boolean humid) {
        for (MyceliumBlock.Part part : MyceliumBlock.Part.values()) {
            AbstractBlock.Settings settings = FabricBlockSettings.create();
            settings = settings
                .mapColor(color)
                .strength(part == MyceliumBlock.Part.CORE ? 1.2f : 0.8f)
                .sounds(BlockSoundGroup.GRAVEL)
                .ticksRandomly();
            MyceliumBlock block = new MyceliumBlock(settings, mushroom, diet, humid, part);
            String name = species + "_" + part.id();
            Registry.register(Registries.BLOCK, id(name), block);
            MyceliumBlock.index(block);
            if (part == MyceliumBlock.Part.CORE) {
                // Предмет є лише в головної грибниці: це «культура», з якої гравець засновує колонію.
                Item item = new BlockItem(block, new FabricItemSettings());
                Registry.register(Registries.ITEM, id(name), item);
                ITEMS.put(name, item);
            }
        }
    }

    private static MyceliumBlock.Diet dietFor(String id, boolean needsTree) {
        if (id.equals("honey_fungus")) {
            return MyceliumBlock.Diet.PARASITE;
        }
        return needsTree ? MyceliumBlock.Diet.ROOTS : MyceliumBlock.Diet.PLANTS;
    }

    @Override
    public void onInitialize() {
        ShroomConfig.load();

        // Поживні запаси областей повільно відновлюються (дерева, дощ, відмерла органіка).
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            if (world.getTime() % ShroomConfig.foodRegenTicks == 0) {
                ColonyData.get(world).regenFood();
            }
        });

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

            // Зморшок: сирим токсичний, після смаження безпечний. Рідкісний. Його грибниця поглинає рослини.
            new Species("morel", MapColor.TERRACOTTA_BROWN, 5, 7, false,
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
            new Species("death_cap", MapColor.GREEN, 7, 7, true,
                food(1, 0.0f)
                    .statusEffect(new StatusEffectInstance(StatusEffects.POISON, 600, 1), 1.0f)
                    .statusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 300), 1.0f)
                    .alwaysEdible().build(),
                List.of(BiomeKeys.FOREST, BiomeKeys.DARK_FOREST, BiomeKeys.FLOWER_FOREST,
                    BiomeKeys.BIRCH_FOREST)),

            // Чорнильний гнойовик: з нього роблять чорнило. Любить вологі місця.
            new Species("inky_cap", MapColor.LIGHT_GRAY, 4, 8, false,
                food(2, 0.2f).build(),
                List.of(BiomeKeys.SWAMP, BiomeKeys.MANGROVE_SWAMP, BiomeKeys.DARK_FOREST, BiomeKeys.MEADOW)),

            // Опеньок осінній: паразит. Його грибниця вбиває дерева, перетворюючи колоди на тліючу
            // деревину, і чим більше дерев, тим сильніша колонія. Сирий токсичний.
            new Species("honey_fungus", MapColor.YELLOW, 8, 7, true,
                food(2, 0.2f)
                    .statusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 200), 1.0f)
                    .alwaysEdible().build(),
                List.of(BiomeKeys.FOREST, BiomeKeys.DARK_FOREST, BiomeKeys.BIRCH_FOREST, BiomeKeys.TAIGA,
                    BiomeKeys.OLD_GROWTH_SPRUCE_TAIGA, BiomeKeys.OLD_GROWTH_PINE_TAIGA)),

            // Мицена хлорофос: крихітний біолюмінесцентний гриб тропічних лісів.
            // Росте купками на мертвій деревині (сапротроф), потребує вологи, живе недовго,
            // їстівність невідома, тому не їжа. Світиться (рівень світла до 5).
            new Species("mycena_chlorophos", MapColor.CYAN, 7, 8, false,
                null,
                List.of(BiomeKeys.JUNGLE, BiomeKeys.SPARSE_JUNGLE, BiomeKeys.BAMBOO_JUNGLE,
                    BiomeKeys.MANGROVE_SWAMP, BiomeKeys.SWAMP),
                5, true, true, false, false),

            // Гібеллула: печерний гриб, паразит павуків (реальний рід Gibellula, відомі й печерні види).
            // Росте у темряві на камені, не потребує дерев; без біомів: генерується під землею.
            new Species("gibellula", MapColor.LIGHT_GRAY, 7, 8, false,
                null,
                List.of(),
                0, false, false, true, true),

            // Пліснява (Penicillium): швидка, невибаглива, але недовговічна. Любить вологу, дерево й сіно.
            new Species("penicillium", MapColor.CYAN, 10, 3, false,
                null,
                List.of(BiomeKeys.SWAMP, BiomeKeys.MANGROVE_SWAMP, BiomeKeys.JUNGLE, BiomeKeys.DARK_FOREST,
                    BiomeKeys.FOREST),
                0, true, true, false, false)
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

            if (s.glow() > 0) {
                final int glow = s.glow();
                settings = settings
                    .luminance(state -> {
                        int age = state.get(ShroomBlock.AGE);
                        return age == 2 ? glow : (age == 1 ? Math.max(1, glow / 2) : 1);
                    })
                    .emissiveLighting((state, world, pos) -> state.get(ShroomBlock.AGE) == 2);
            }

            Block block = new ShroomBlock(settings, s.width(), s.height(), s.needsTree(), id(s.id()),
                s.onWood(), s.humid(), s.onStone(), s.needsDark());
            Registry.register(Registries.BLOCK, id(s.id()), block);
            BLOCKS.put(s.id(), block);

            // Їжа, яку можна і з'їсти, і посадити (як солодкі ягоди).
            Item.Settings itemSettings = new FabricItemSettings();
            if (s.food() != null) {
                itemSettings = itemSettings.food(s.food());
            }
            Item item = new AliasedBlockItem(block, itemSettings);
            Registry.register(Registries.ITEM, id(s.id()), item);
            ITEMS.put(s.id(), item);

            CompostingChanceRegistry.INSTANCE.add(item, 0.3f);

            // Грибниця цього виду: головна, вузол і гіфи.
            registerMycelium(s.id(), id(s.id()), s.color(), dietFor(s.id(), s.needsTree()), s.humid());

            // Природна генерація: див. data/realshrooms/worldgen/.
            RegistryKey<PlacedFeature> placed = RegistryKey.of(RegistryKeys.PLACED_FEATURE, id(s.id()));
            if (s.biomes().isEmpty()) {
                // Печерний вид: у всьому Верхньому світі, під землею.
                BiomeModifications.addFeature(
                    BiomeSelectors.foundInOverworld(),
                    GenerationStep.Feature.UNDERGROUND_DECORATION,
                    placed);
            } else {
                BiomeModifications.addFeature(
                    BiomeSelectors.includeByKey(s.biomes()),
                    GenerationStep.Feature.VEGETAL_DECORATION,
                    placed);
            }
        }

        // Смажений зморшок.
        Item cookedMorel = new Item(new FabricItemSettings().food(food(6, 0.6f).build()));
        Registry.register(Registries.ITEM, id("cooked_morel"), cookedMorel);
        ITEMS.put("cooked_morel", cookedMorel);

        // Дріжжі: підгодовують грибницю (ПКМ по ній) і використовуються для хліба на заквасці.
        Item yeast = new YeastItem(new FabricItemSettings());
        Registry.register(Registries.ITEM, id("yeast"), yeast);
        ITEMS.put("yeast", yeast);
        Item sourdough = new Item(new FabricItemSettings().food(food(7, 0.8f).build()));
        Registry.register(Registries.ITEM, id("sourdough_bread"), sourdough);
        ITEMS.put("sourdough_bread", sourdough);

        // Шашлик із грибів.
        Item skewer = new Item(new FabricItemSettings().food(food(7, 0.7f).build()));
        Registry.register(Registries.ITEM, id("mushroom_skewer"), skewer);
        ITEMS.put("mushroom_skewer", skewer);

        // Грибний субстрат: грядка для вирощування грибів.
        AbstractBlock.Settings substrateSettings = FabricBlockSettings.create();
        substrateSettings = substrateSettings
            .mapColor(MapColor.BROWN)
            .strength(0.6f)
            .sounds(BlockSoundGroup.GRAVEL)
            .ticksRandomly();
        SUBSTRATE = new SubstrateBlock(substrateSettings);
        Registry.register(Registries.BLOCK, id("mushroom_substrate"), SUBSTRATE);
        Item substrateItem = new BlockItem(SUBSTRATE, new FabricItemSettings());
        Registry.register(Registries.ITEM, id("mushroom_substrate"), substrateItem);
        ITEMS.put("mushroom_substrate", substrateItem);

        // Ванільні гриби в нашій системі: свої колонії (коричневий: поглинає рослини, червоний: коріння дерев).
        registerMycelium("brown_mushroom", new Identifier("minecraft", "brown_mushroom"),
            MapColor.BROWN, MyceliumBlock.Diet.PLANTS, false);
        registerMycelium("red_mushroom", new Identifier("minecraft", "red_mushroom"),
            MapColor.RED, MyceliumBlock.Diet.ROOTS, false);

        // Травоїдні тварини їдять гриби й грибницю.
        GrazingHandler.register();

        // Режим відладки: команда /realshrooms.
        DebugCommands.register();

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.NATURAL).register(entries -> {
            for (Map.Entry<String, Item> e : ITEMS.entrySet()) {
                String key = e.getKey();
                if (!key.equals("cooked_morel") && !key.equals("mushroom_skewer") && !key.equals("sourdough_bread")) {
                    entries.add(e.getValue());
                }
            }
        });
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FOOD_AND_DRINK).register(entries -> {
            for (Map.Entry<String, Item> e : ITEMS.entrySet()) {
                if (!e.getKey().equals("mushroom_substrate") && !e.getKey().endsWith("_core")) {
                    entries.add(e.getValue());
                }
            }
        });
    }
}
