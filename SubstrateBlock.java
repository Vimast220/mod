package com.realshrooms;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.WorldView;

/**
 * Грибний субстрат: грядка для вирощування грибів.
 * Як і грядка для рослин, може бути вологою (вода в радіусі 4 блоків або дощ) чи сухою.
 * На вологому субстраті гриби ростуть швидко, а мікоризні види там ростуть і без дерев.
 */
public class SubstrateBlock extends Block {
    public static final BooleanProperty MOIST = BooleanProperty.of("moist");

    public SubstrateBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState().with(MOIST, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(MOIST);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        return this.getDefaultState().with(MOIST, hasWaterNearby(context.getWorld(), context.getBlockPos()));
    }

    @Override
    public void randomTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        boolean wet = hasWaterNearby(world, pos) || world.hasRain(pos.up());
        if (state.get(MOIST) != wet) {
            world.setBlockState(pos, state.with(MOIST, wet), Block.NOTIFY_LISTENERS);
        }
    }

    public static boolean hasWaterNearby(WorldView world, BlockPos pos) {
        for (BlockPos nearby : BlockPos.iterate(pos.add(-4, 0, -4), pos.add(4, 1, 4))) {
            if (world.getFluidState(nearby).isIn(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }
}
