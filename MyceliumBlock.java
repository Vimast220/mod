package com.realshrooms;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.Fertilizable;
import net.minecraft.block.SaplingBlock;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;

/**
 * Грибниця: живий організм, який складається з багатьох блоків.
 *
 * Кожен блок має «силу» (VIGOR, 0..7), як рівень освітлення:
 *  - стигла грибна ніжка над блоком дає йому повну силу (7);
 *  - сила розтікається сусідніми блоками тієї ж грибниці, слабшаючи на 1 за блок;
 *  - без годування сила повільно спадає, а при нулі грибниця вмирає і ґрунт знову стає землею;
 *  - сильна грибниця (3+) захоплює сусідні блоки землі, а на краю колонії (сила 3..5) виростають гриби.
 *
 * Зовнішнє живлення (Diet):
 *  - ROOTS: живе в корінні дерев і живиться водою та ґрунтом. Без дерев поруч гине. Поруч із деревом
 *    має базове живлення (вище, коли волого: вода поруч або дощ), а ще допомагає саджанцям рости;
 *  - PLANTS: поглинає рослини (трава, квіти, папороть, саджанці, ягідні кущі, інші гриби) навколо
 *    себе. Кожна «поїдена» рослина дає колонії енергію, а коли їжа скінчилась, колонія голодує.
 */
public class MyceliumBlock extends Block implements Fertilizable {
    public enum Diet { ROOTS, PLANTS }

    public static final int MAX_VIGOR = 7;
    public static final IntProperty VIGOR = IntProperty.of("vigor", 0, MAX_VIGOR);

    private final Identifier mushroomId;
    private final Diet diet;
    private final boolean needsMoisture;

    public MyceliumBlock(Settings settings, Identifier mushroomId, Diet diet, boolean needsMoisture) {
        super(settings);
        this.mushroomId = mushroomId;
        this.diet = diet;
        this.needsMoisture = needsMoisture;
        // Грибниця, яку гравець поставив сам, починає з повною силою.
        this.setDefaultState(this.stateManager.getDefaultState().with(VIGOR, MAX_VIGOR));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(VIGOR);
    }

    @Override
    public void randomTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        int vigor = state.get(VIGOR);
        boolean roots = this.diet == Diet.ROOTS;
        boolean wet = world.hasRain(pos.up()) || SubstrateBlock.hasWaterNearby(world, pos);
        // Вологолюбна грибниця без вологи голодує, як і та, що живе в корінні без дерев.
        boolean hostOk = (!roots || hasHostTree(world, pos)) && (!this.needsMoisture || wet);

        // 1. Скільки «живлення» доходить до цього блока.
        int target = -1;
        if (hostOk) {
            if (isFed(world, pos)) {
                target = MAX_VIGOR;
            }
            if (roots) {
                // Коріння дерев: вода й ґрунт дають базове живлення, у вологу пору більше.
                target = Math.max(target, wet ? 4 : 2);
            } else if (graze(world, pos, random)) {
                // Поглинена рослина: різкий приплив енергії.
                vigor = Math.min(MAX_VIGOR, vigor + 3);
                target = MAX_VIGOR;
            }
            for (BlockPos nearby : BlockPos.iterate(pos.add(-1, -1, -1), pos.add(1, 1, 1))) {
                if (nearby.equals(pos)) {
                    continue;
                }
                BlockState neighbour = world.getBlockState(nearby);
                if (neighbour.isOf(this)) {
                    target = Math.max(target, neighbour.get(VIGOR) - 1);
                }
            }
        }

        // 2. Сила повільно тягнеться до цільової; без годування грибниця вмирає.
        if (target < 0 && vigor == 0) {
            world.setBlockState(pos, Blocks.DIRT.getDefaultState(), Block.NOTIFY_LISTENERS);
            return;
        }
        int newVigor = vigor;
        if (target > vigor) {
            newVigor = vigor + 1;
        } else if (target < vigor) {
            newVigor = vigor - 1;
        }
        if (newVigor != state.get(VIGOR)) {
            state = state.with(VIGOR, newVigor);
            world.setBlockState(pos, state, Block.NOTIFY_LISTENERS);
        }
        if (!hostOk) {
            return;
        }

        // 3. Сильна грибниця захоплює сусідні блоки (у вологу пору швидше).
        if (newVigor >= 3 && random.nextInt(wet ? 3 : 6) == 0) {
            spread(world, pos, newVigor, random);
        }

        // 4. На краю колонії виростають гриби («відьмине коло»).
        if (newVigor >= 3 && newVigor <= 5 && random.nextInt(wet ? 4 : 8) == 0) {
            fruit(world, pos, random);
        }

        // 5. Симбіоз: грибниця в корінні дерев підгодовує саджанці над собою.
        if (roots && newVigor >= 4 && random.nextInt(4) == 0) {
            BlockPos above = pos.up();
            BlockState aboveState = world.getBlockState(above);
            if (aboveState.getBlock() instanceof SaplingBlock sapling) {
                sapling.generate(world, above, aboveState, random);
            }
        }
    }

    private boolean isFed(ServerWorld world, BlockPos pos) {
        BlockState above = world.getBlockState(pos.up());
        return above.isOf(Registries.BLOCK.get(this.mushroomId))
            && above.get(ShroomBlock.AGE) == 2;
    }

    /** Поглинає одну рослину в шарі над блоком (3x3). Повертає true, якщо щось «з'їла». */
    private boolean graze(ServerWorld world, BlockPos pos, Random random) {
        for (int attempt = 0; attempt < 3; attempt++) {
            BlockPos prey = pos.add(random.nextInt(3) - 1, 1, random.nextInt(3) - 1);
            if (isPrey(world.getBlockState(prey))) {
                world.breakBlock(prey, false);
                return true;
            }
        }
        return false;
    }

    private static boolean isPrey(BlockState state) {
        return state.isIn(BlockTags.FLOWERS)
            || state.isIn(BlockTags.SAPLINGS)
            || state.isOf(Blocks.GRASS)
            || state.isOf(Blocks.TALL_GRASS)
            || state.isOf(Blocks.FERN)
            || state.isOf(Blocks.LARGE_FERN)
            || state.isOf(Blocks.DEAD_BUSH)
            || state.isOf(Blocks.SWEET_BERRY_BUSH)
            || state.isOf(Blocks.BROWN_MUSHROOM)
            || state.isOf(Blocks.RED_MUSHROOM);
    }

    private void spread(ServerWorld world, BlockPos pos, int vigor, Random random) {
        int dx = random.nextInt(3) - 1;
        int dy = random.nextInt(3) - 1;
        int dz = random.nextInt(3) - 1;
        if (dx == 0 && dy == 0 && dz == 0) {
            return;
        }
        BlockPos target = pos.add(dx, dy, dz);
        BlockState targetState = world.getBlockState(target);
        if (!targetState.isIn(BlockTags.DIRT)
            || targetState.getBlock() instanceof MyceliumBlock
            || targetState.getBlock() instanceof SubstrateBlock) {
            return;
        }
        if (this.diet == Diet.ROOTS && !hasHostTree(world, target)) {
            return;
        }
        world.setBlockState(target, this.getDefaultState().with(VIGOR, Math.max(1, vigor - 2)), Block.NOTIFY_LISTENERS);
    }

    private void fruit(ServerWorld world, BlockPos pos, Random random) {
        BlockPos above = pos.up();
        if (!world.isAir(above)) {
            return;
        }
        Block mushroom = Registries.BLOCK.get(this.mushroomId);
        if (!(mushroom instanceof ShroomBlock)) {
            return;
        }
        // Не більше 2 однакових грибів у радіусі 2 блоків.
        int count = 0;
        for (BlockPos nearby : BlockPos.iterate(pos.add(-2, 0, -2), pos.add(2, 1, 2))) {
            if (world.getBlockState(nearby).isOf(mushroom)) {
                count++;
            }
        }
        if (count > 2) {
            return;
        }
        BlockState seedling = mushroom.getDefaultState().with(ShroomBlock.AGE, 0);
        if (seedling.canPlaceAt(world, above)) {
            world.setBlockState(above, seedling, Block.NOTIFY_LISTENERS);
        }
    }

    private static boolean hasHostTree(WorldView world, BlockPos pos) {
        for (BlockPos nearby : BlockPos.iterate(pos.add(-3, -1, -3), pos.add(3, 5, 3))) {
            if (world.getBlockState(nearby).isIn(BlockTags.LOGS)) {
                return true;
            }
        }
        return false;
    }

    // ---------- кісткове борошно: підживлює колонію ----------

    @Override
    public boolean isFertilizable(WorldView world, BlockPos pos, BlockState state, boolean isClient) {
        return state.get(VIGOR) < MAX_VIGOR;
    }

    @Override
    public boolean canGrow(World world, Random random, BlockPos pos, BlockState state) {
        return true;
    }

    @Override
    public void grow(ServerWorld world, Random random, BlockPos pos, BlockState state) {
        int vigor = Math.min(MAX_VIGOR, state.get(VIGOR) + 3);
        world.setBlockState(pos, state.with(VIGOR, vigor), Block.NOTIFY_LISTENERS);
        spread(world, pos, vigor, random);
    }

    // ---------- візуально: жива грибниця «димить» спорами ----------

    @Environment(EnvType.CLIENT)
    @Override
    public void randomDisplayTick(BlockState state, World world, BlockPos pos, Random random) {
        if (state.get(VIGOR) >= 5 && random.nextInt(24) == 0 && world.getBlockState(pos.up()).isAir()) {
            world.addParticle(ParticleTypes.MYCELIUM,
                pos.getX() + random.nextDouble(), pos.getY() + 1.05, pos.getZ() + random.nextDouble(),
                0.0, 0.01, 0.0);
        }
    }
}
