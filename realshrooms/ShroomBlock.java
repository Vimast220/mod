package com.realshrooms;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Fertilizable;
import net.minecraft.block.PlantBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;

/**
 * Гриб із трьома стадіями росту: 0 = зав'язь, 1 = молодий, 2 = стиглий.
 *
 * Садівництво:
 *  - гриби ростуть повільно; дощ і волога грибна грядка (SubstrateBlock) прискорюють ріст;
 *  - мікоризні види без дерев поруч ростуть лише на грибному субстраті;
 *  - ПКМ по стиглому грибу збирає врожай, гриб відростає (з ножицями: +1 гриб і швидший відріст);
 *  - кісткове борошно прискорює ріст;
 *  - стиглі гриби поширюються (після дощу набагато швидше).
 */
public class ShroomBlock extends PlantBlock implements Fertilizable {
    public static final IntProperty AGE = Properties.AGE_2;
    private static final double[] STAGE_SCALE = {0.45, 0.75, 1.0};

    private final VoxelShape[] shapes = new VoxelShape[3];
    private final boolean needsTree;
    private final Identifier itemId;
    private final boolean onWood;
    private final boolean humid;

    public ShroomBlock(AbstractBlock.Settings settings, int width, int height, boolean needsTree, Identifier itemId,
                       boolean onWood, boolean humid) {
        super(settings);
        this.needsTree = needsTree;
        this.itemId = itemId;
        this.onWood = onWood;
        this.humid = humid;
        for (int i = 0; i < 3; i++) {
            double w = Math.max(2.0, width * STAGE_SCALE[i]);
            double h = Math.max(2.0, height * STAGE_SCALE[i]);
            double min = 8.0 - w / 2.0;
            double max = 8.0 + w / 2.0;
            this.shapes[i] = Block.createCuboidShape(min, 0.0, min, max, h, max);
        }
        // Гриби, що з'явились при генерації світу, одразу стиглі.
        this.setDefaultState(this.stateManager.getDefaultState().with(AGE, 2));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(AGE);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        // Посаджений гравцем гриб починає з зав'язі.
        BlockState state = super.getPlacementState(context);
        return state == null ? null : state.with(AGE, 0);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return this.shapes[state.get(AGE)];
    }

    @Override
    protected boolean canPlantOnTop(BlockState floor, BlockView world, BlockPos pos) {
        return floor.isIn(BlockTags.DIRT)
            || floor.getBlock() instanceof SubstrateBlock
            || floor.getBlock() instanceof MyceliumBlock
            || (this.onWood && floor.isIn(BlockTags.LOGS));
    }

    // ---------- ріст і поширення ----------

    @Override
    public void randomTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        BlockState floor = world.getBlockState(pos.down());
        boolean substrate = floor.getBlock() instanceof SubstrateBlock;
        boolean mycelium = floor.getBlock() instanceof MyceliumBlock;
        boolean cultivated = substrate || mycelium;
        boolean moist = (substrate && floor.get(SubstrateBlock.MOIST))
            || (mycelium && floor.get(MyceliumBlock.VIGOR) >= 4);

        // Мікориза: дикі види без дерев поруч не ростуть і не поширюються.
        // Грибниця й субстрат уже є «організмом», тож дерева їм не потрібні (їх перевіряє сама грибниця).
        if (this.needsTree && !cultivated && !hasTreeNearby(world, pos)) {
            return;
        }

        // Вологолюбні види (напр. мицена) ростуть лише в сирості: дощ, вода поруч або вологий ґрунт.
        boolean wetEnough = !this.humid || moist || world.hasRain(pos) || SubstrateBlock.hasWaterNearby(world, pos);

        int age = state.get(AGE);
        if (age < 2) {
            if (!wetEnough) {
                return;
            }
            int denominator;
            if (mycelium) {
                denominator = moist ? 4 : 10;
            } else if (substrate) {
                denominator = moist ? 4 : 24;
            } else {
                denominator = 12;
            }
            if (world.hasRain(pos)) {
                denominator = Math.max(2, denominator / 2);
            }
            if (random.nextInt(denominator) == 0) {
                world.setBlockState(pos, state.with(AGE, age + 1), Block.NOTIFY_LISTENERS);
            }
            return;
        }

        // Ніжні вологолюбні гриби живуть недовго: у посуху плодове тіло в'яне.
        if (!wetEnough && random.nextInt(30) == 0) {
            world.breakBlock(pos, false);
            return;
        }

        // Стигла грибна ніжка «захоплює» ґрунт: під нею виникає грибниця цього виду.
        if (!cultivated && floor.isIn(BlockTags.DIRT) && random.nextInt(world.isRaining() ? 8 : 20) == 0) {
            Block colony = Registries.BLOCK.get(
                new Identifier(this.itemId.getNamespace(), this.itemId.getPath() + "_mycelium"));
            if (colony instanceof MyceliumBlock) {
                world.setBlockState(pos.down(), colony.getDefaultState().with(MyceliumBlock.VIGOR, 5), Block.NOTIFY_LISTENERS);
                return;
            }
        }

        // Стиглі гриби поширюються.
        int chance = world.isRaining() ? 15 : 60;
        if (moist) {
            chance /= 2;
        }
        if (random.nextInt(chance) != 0) {
            return;
        }

        // Не більше 5 однакових грибів у радіусі 4 блоків.
        int room = 5;
        for (BlockPos nearby : BlockPos.iterate(pos.add(-4, -1, -4), pos.add(4, 1, 4))) {
            if (world.getBlockState(nearby).isOf(this) && --room <= 0) {
                return;
            }
        }

        BlockState seedling = state.with(AGE, 0);
        BlockPos origin = pos;
        BlockPos target = origin.add(random.nextInt(3) - 1, random.nextInt(2) - random.nextInt(2), random.nextInt(3) - 1);
        for (int i = 0; i < 4; i++) {
            if (world.isAir(target) && seedling.canPlaceAt(world, target)) {
                origin = target;
            }
            target = origin.add(random.nextInt(3) - 1, random.nextInt(2) - random.nextInt(2), random.nextInt(3) - 1);
        }

        if (world.isAir(target) && seedling.canPlaceAt(world, target)) {
            world.setBlockState(target, seedling, Block.NOTIFY_LISTENERS);
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

    // ---------- збір врожаю ----------

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (state.get(AGE) < 2) {
            return ActionResult.PASS;
        }

        ItemStack held = player.getStackInHand(hand);
        boolean shears = held.isOf(Items.SHEARS);

        if (!world.isClient) {
            int count = 1 + world.random.nextInt(2) + (shears ? 1 : 0);
            Block.dropStack(world, pos, new ItemStack(Registries.ITEM.get(this.itemId), count));
            world.setBlockState(pos, state.with(AGE, shears ? 1 : 0), Block.NOTIFY_LISTENERS);
            world.playSound(null, pos, SoundEvents.ITEM_SWEET_BERRIES_PICK_FROM_BUSH, SoundCategory.BLOCKS,
                1.0f, 0.8f + world.random.nextFloat() * 0.4f);
            if (world instanceof ServerWorld serverWorld) {
                serverWorld.spawnParticles(ParticleTypes.MYCELIUM,
                    pos.getX() + 0.5, pos.getY() + 0.3, pos.getZ() + 0.5, 12, 0.25, 0.15, 0.25, 0.01);
            }
            if (shears && !player.getAbilities().creativeMode) {
                held.damage(1, player, p -> p.sendToolBreakStatus(hand));
            }
        }
        return ActionResult.success(world.isClient);
    }

    // ---------- кісткове борошно ----------

    @Override
    public boolean isFertilizable(WorldView world, BlockPos pos, BlockState state, boolean isClient) {
        return state.get(AGE) < 2;
    }

    @Override
    public boolean canGrow(World world, Random random, BlockPos pos, BlockState state) {
        return true;
    }

    @Override
    public void grow(ServerWorld world, Random random, BlockPos pos, BlockState state) {
        world.setBlockState(pos, state.with(AGE, Math.min(2, state.get(AGE) + 1)), Block.NOTIFY_LISTENERS);
    }

    // ---------- візуальний натяк: стиглі гриби «спорять» ----------

    @Environment(EnvType.CLIENT)
    @Override
    public void randomDisplayTick(BlockState state, World world, BlockPos pos, Random random) {
        if (state.get(AGE) == 2 && random.nextInt(12) == 0) {
            world.addParticle(state.getLuminance() > 0 ? ParticleTypes.GLOW : ParticleTypes.MYCELIUM,
                pos.getX() + 0.3 + random.nextDouble() * 0.4,
                pos.getY() + 0.2 + random.nextDouble() * 0.3,
                pos.getZ() + 0.3 + random.nextDouble() * 0.4,
                0.0, 0.01, 0.0);
        }
    }
}
