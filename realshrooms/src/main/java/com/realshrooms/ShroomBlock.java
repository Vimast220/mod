package com.realshrooms;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.PlantBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;

/**
 * Гриб, який росте на ґрунті (земля, трава, підзол, мох, міцелій).
 *
 * Реалістичність:
 *  - після дощу гриби поширюються набагато швидше;
 *  - мікоризні види (needsTree = true) поширюються тільки поруч із деревами;
 *  - гриби не збиваються в надто щільні гущі.
 */
public class ShroomBlock extends PlantBlock {
    private final VoxelShape shape;
    private final boolean needsTree;

    public ShroomBlock(AbstractBlock.Settings settings, int width, int height, boolean needsTree) {
        super(settings);
        double min = 8.0 - width / 2.0;
        double max = 8.0 + width / 2.0;
        this.shape = Block.createCuboidShape(min, 0.0, min, max, height, max);
        this.needsTree = needsTree;
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return this.shape;
    }

    @Override
    protected boolean canPlantOnTop(BlockState floor, BlockView world, BlockPos pos) {
        return floor.isIn(BlockTags.DIRT);
    }

    @Override
    public void randomTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        // Під дощем гриби «вилазять» приблизно в чотири рази частіше.
        int chance = world.isRaining() ? 15 : 60;
        if (random.nextInt(chance) != 0) {
            return;
        }

        if (this.needsTree && !hasTreeNearby(world, pos)) {
            return;
        }

        // Не більше 5 однакових грибів у радіусі 4 блоків.
        int room = 5;
        for (BlockPos nearby : BlockPos.iterate(pos.add(-4, -1, -4), pos.add(4, 1, 4))) {
            if (world.getBlockState(nearby).isOf(this) && --room <= 0) {
                return;
            }
        }

        BlockPos origin = pos;
        BlockPos target = origin.add(random.nextInt(3) - 1, random.nextInt(2) - random.nextInt(2), random.nextInt(3) - 1);
        for (int i = 0; i < 4; i++) {
            if (world.isAir(target) && state.canPlaceAt(world, target)) {
                origin = target;
            }
            target = origin.add(random.nextInt(3) - 1, random.nextInt(2) - random.nextInt(2), random.nextInt(3) - 1);
        }

        if (world.isAir(target) && state.canPlaceAt(world, target)) {
            world.setBlockState(target, state, Block.NOTIFY_LISTENERS);
        }
    }

    private static boolean hasTreeNearby(ServerWorld world, BlockPos pos) {
        for (BlockPos nearby : BlockPos.iterate(pos.add(-3, 0, -3), pos.add(3, 4, 3))) {
            if (world.getBlockState(nearby).isIn(BlockTags.LOGS)) {
                return true;
            }
        }
        return false;
    }
}
