package com.realshrooms;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.Fertilizable;
import net.minecraft.block.SaplingBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;

/**
 * Грибниця: живий організм, який складається з багатьох блоків.
 *
 * Сила (VIGOR, 0..7), як рівень світла: стигле плодове тіло над блоком дає повну силу, вона розтікається
 * сусідніми блоками тієї ж грибниці (-1 за блок), без годування спадає, а при нулі грибниця вмирає.
 *
 * Живлення (Diet): ROOTS (коріння дерев: вода й ґрунт) або PLANTS (поглинає рослини).
 *
 * Очки колонії (ColonyData): колонія заробляє їх, коли її годують, і витрачає на захоплення блоків,
 * плодоношення та апгрейди (експансія, плодоношення, стійкість, агресія).
 *
 * Війни: грибниця іншого виду, що стоїть на шляху, оспорюється. Сила нападу = сила блока + 2*агресія + кидок,
 * сила захисту = сила блока цілі + 2*стійкість + кидок. Переможець захоплює блок і забирає очко.
 *
 * Ванільні гриби: коричневий і червоний гриби теж мають свої колонії, а ванільні гриби та великі гриби
 * (стовбур і шапки) годують будь-яку грибницю як плодові тіла.
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

    /** Назва виду, наприклад «chanterelle» або «brown_mushroom». */
    public String species() {
        return this.mushroomId.getPath();
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(VIGOR);
    }

    @Override
    public void randomTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        ColonyData data = ColonyData.get(world);
        String key = ColonyData.key(species(), pos);
        ColonyData.Colony colony = data.colony(key);
        int expansion = colony.levels[ColonyData.EXPANSION];
        int fruiting = colony.levels[ColonyData.FRUITING];
        int resilience = colony.levels[ColonyData.RESILIENCE];
        int aggression = colony.levels[ColonyData.AGGRESSION];

        int vigor = state.get(VIGOR);
        boolean roots = this.diet == Diet.ROOTS;
        boolean wet = world.hasRain(pos.up()) || SubstrateBlock.hasWaterNearby(world, pos);
        // Вологолюбна грибниця без вологи голодує, як і та, що живе в корінні без дерев.
        boolean hostOk = (!roots || hasHostTree(world, pos)) && (!this.needsMoisture || wet);

        // 1. Скільки «живлення» доходить до цього блока і скільки очок воно приносить.
        int earned = 0;
        int target = -1;
        if (hostOk) {
            if (isFed(world, pos)) {
                target = MAX_VIGOR;
                earned += 1;
            }
            if (roots) {
                // Коріння дерев: вода й ґрунт дають базове живлення, у вологу пору більше.
                target = Math.max(target, wet ? 4 : 2);
                if (wet && random.nextInt(4) == 0) {
                    earned += 1;
                }
            } else if (graze(world, pos, random)) {
                // Поглинена рослина: різкий приплив енергії.
                vigor = Math.min(MAX_VIGOR, vigor + 3);
                target = MAX_VIGOR;
                earned += 2;
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
        if (earned > 0) {
            data.addPoints(key, earned);
        }

        // 2. Сила повільно тягнеться до цільової. Стійкість уповільнює згасання.
        if (target < 0 && vigor == 0) {
            if (random.nextInt(resilience + 1) == 0) {
                world.setBlockState(pos, Blocks.DIRT.getDefaultState(), Block.NOTIFY_LISTENERS);
            }
            return;
        }
        int newVigor = vigor;
        if (target > vigor) {
            newVigor = vigor + 1;
        } else if (target < vigor && random.nextInt(resilience + 1) == 0) {
            newVigor = vigor - 1;
        }
        if (newVigor != state.get(VIGOR)) {
            state = state.with(VIGOR, newVigor);
            world.setBlockState(pos, state, Block.NOTIFY_LISTENERS);
        }
        if (!hostOk) {
            return;
        }

        // 3. Захоплення сусідніх блоків і війни. Експансія пришвидшує, а голодна колонія повільніша.
        int spreadChance = Math.max(1, (wet ? 3 : 6) - expansion);
        if (colony.points <= 0) {
            spreadChance += 3;
        }
        if (newVigor >= 3 && random.nextInt(spreadChance) == 0) {
            spread(world, pos, newVigor, random, data, key, aggression);
        }

        // 4. На краю колонії виростають гриби («відьмине коло»). Це коштує очко.
        int maxFruitVigor = fruiting >= 2 ? 6 : 5;
        int fruitChance = Math.max(2, (wet ? 4 : 8) - fruiting);
        if (newVigor >= 3 && newVigor <= maxFruitVigor && colony.points > 0 && random.nextInt(fruitChance) == 0) {
            if (fruit(world, pos)) {
                data.spend(key, 1);
            }
        }

        // 5. Симбіоз: грибниця в корінні дерев підгодовує саджанці над собою.
        if (roots && newVigor >= 4 && random.nextInt(4) == 0) {
            BlockPos above = pos.up();
            BlockState aboveState = world.getBlockState(above);
            if (aboveState.getBlock() instanceof SaplingBlock sapling) {
                sapling.generate(world, above, aboveState, random);
            }
        }

        // 6. Розвиток: колонія вкладає накопичені очки в апгрейд за «характером» виду.
        if (random.nextInt(4) == 0) {
            int bought = data.tryUpgrade(key, ColonyData.priority(species()));
            if (bought >= 0) {
                announce(world, pos, bought, colony.levels[bought]);
            }
        }
    }

    private boolean isFed(ServerWorld world, BlockPos pos) {
        BlockState above = world.getBlockState(pos.up());
        // Великі ванільні гриби годують будь-яку грибницю.
        if (above.isOf(Blocks.MUSHROOM_STEM) || above.isOf(Blocks.RED_MUSHROOM_BLOCK) || above.isOf(Blocks.BROWN_MUSHROOM_BLOCK)) {
            return true;
        }
        if (!above.isOf(Registries.BLOCK.get(this.mushroomId))) {
            return false;
        }
        // Наші гриби мають стадії росту, ванільні завжди «стиглі».
        return !above.contains(ShroomBlock.AGE) || above.get(ShroomBlock.AGE) == 2;
    }

    /** Поглинає одну рослину в шарі над блоком (3x3). Повертає true, якщо щось «з'їла». */
    private boolean graze(ServerWorld world, BlockPos pos, Random random) {
        Block own = Registries.BLOCK.get(this.mushroomId);
        for (int attempt = 0; attempt < 3; attempt++) {
            BlockPos preyPos = pos.add(random.nextInt(3) - 1, 1, random.nextInt(3) - 1);
            BlockState prey = world.getBlockState(preyPos);
            if (isPrey(prey) && !prey.isOf(own)) {
                world.breakBlock(preyPos, false);
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

    private static boolean isMushroom(BlockState state) {
        return state.getBlock() instanceof ShroomBlock
            || state.isOf(Blocks.BROWN_MUSHROOM)
            || state.isOf(Blocks.RED_MUSHROOM);
    }

    private void spread(ServerWorld world, BlockPos pos, int vigor, Random random,
                        ColonyData data, String key, int aggression) {
        int dx = random.nextInt(3) - 1;
        int dy = random.nextInt(3) - 1;
        int dz = random.nextInt(3) - 1;
        if (dx == 0 && dy == 0 && dz == 0) {
            return;
        }
        BlockPos target = pos.add(dx, dy, dz);
        BlockState targetState = world.getBlockState(target);

        // Війна з грибницею іншого виду.
        if (targetState.getBlock() instanceof MyceliumBlock other) {
            if (other != this) {
                fight(world, pos, vigor, target, targetState, other, random, data, key, aggression);
            }
            return;
        }
        // Ванільна грибниця: слабкий нейтральний захисник.
        if (targetState.isOf(Blocks.MYCELIUM)) {
            if (vigor + 2 * aggression + random.nextInt(3) > 4 + random.nextInt(3)) {
                claim(world, target, vigor);
            }
            return;
        }

        if (!targetState.isIn(BlockTags.DIRT) || targetState.getBlock() instanceof SubstrateBlock) {
            return;
        }
        if (this.diet == Diet.ROOTS && !hasHostTree(world, target)) {
            return;
        }
        data.spend(key, 1);
        claim(world, target, vigor);
    }

    private void claim(ServerWorld world, BlockPos target, int vigor) {
        world.setBlockState(target, this.getDefaultState().with(VIGOR, Math.max(1, vigor - 2)), Block.NOTIFY_LISTENERS);
    }

    private void fight(ServerWorld world, BlockPos pos, int vigor, BlockPos target, BlockState targetState,
                       MyceliumBlock other, Random random, ColonyData data, String key, int aggression) {
        String otherKey = ColonyData.key(other.species(), target);
        ColonyData.Colony defender = data.colony(otherKey);
        int attack = vigor + 2 * aggression + random.nextInt(3);
        int defence = targetState.get(VIGOR) + 2 * defender.levels[ColonyData.RESILIENCE] + random.nextInt(3);

        if (attack > defence) {
            // Перемога: блок захоплено, переможець забирає очко, плодові тіла суперника в'януть.
            claim(world, target, vigor);
            data.addPoints(key, 1);
            data.spend(otherKey, 1);
            BlockPos above = target.up();
            BlockState aboveState = world.getBlockState(above);
            if (isMushroom(aboveState) && !aboveState.isOf(Registries.BLOCK.get(this.mushroomId))) {
                world.breakBlock(above, false);
            }
            world.spawnParticles(ParticleTypes.SPORE_BLOSSOM_AIR,
                target.getX() + 0.5, target.getY() + 1.0, target.getZ() + 0.5, 8, 0.3, 0.2, 0.3, 0.01);
        } else {
            // Поразка: нападник слабшає, захисник отримує очко.
            BlockState self = world.getBlockState(pos);
            if (self.isOf(this) && self.get(VIGOR) > 0) {
                world.setBlockState(pos, self.with(VIGOR, self.get(VIGOR) - 1), Block.NOTIFY_LISTENERS);
            }
            data.addPoints(otherKey, 1);
        }
    }

    private boolean fruit(ServerWorld world, BlockPos pos) {
        BlockPos above = pos.up();
        if (!world.isAir(above)) {
            return false;
        }
        Block mushroom = Registries.BLOCK.get(this.mushroomId);
        if (mushroom == Blocks.AIR) {
            return false;
        }
        // Не більше 2 однакових грибів у радіусі 2 блоків.
        int count = 0;
        for (BlockPos nearby : BlockPos.iterate(pos.add(-2, 0, -2), pos.add(2, 1, 2))) {
            if (world.getBlockState(nearby).isOf(mushroom)) {
                count++;
            }
        }
        if (count > 2) {
            return false;
        }
        BlockState seedling = mushroom.getDefaultState();
        if (seedling.contains(ShroomBlock.AGE)) {
            seedling = seedling.with(ShroomBlock.AGE, 0);
        }
        if (seedling.canPlaceAt(world, above)) {
            world.setBlockState(above, seedling, Block.NOTIFY_LISTENERS);
            return true;
        }
        return false;
    }

    private static boolean hasHostTree(WorldView world, BlockPos pos) {
        for (BlockPos nearby : BlockPos.iterate(pos.add(-3, -1, -3), pos.add(3, 5, 3))) {
            if (world.getBlockState(nearby).isIn(BlockTags.LOGS)) {
                return true;
            }
        }
        return false;
    }

    private void announce(ServerWorld world, BlockPos pos, int upgrade, int level) {
        Text message = Text.translatable("message.realshrooms.upgrade", this.getName(),
            Text.translatable("upgrade.realshrooms." + ColonyData.UPGRADE_NAMES[upgrade]), level);
        for (ServerPlayerEntity player : world.getPlayers(p -> p.squaredDistanceTo(pos.getX(), pos.getY(), pos.getZ()) < 1024.0)) {
            player.sendMessage(message, true);
        }
    }

    // ---------- оглядання колонії: ПКМ порожньою рукою ----------

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (hand != Hand.MAIN_HAND || !player.getStackInHand(hand).isEmpty()) {
            return ActionResult.PASS;
        }
        if (world instanceof ServerWorld serverWorld) {
            ColonyData.Colony colony = ColonyData.get(serverWorld).colony(ColonyData.key(species(), pos));
            player.sendMessage(Text.translatable("message.realshrooms.colony", this.getName(), colony.points,
                colony.levels[0], colony.levels[1], colony.levels[2], colony.levels[3], state.get(VIGOR)), false);
        }
        return ActionResult.success(world.isClient);
    }

    // ---------- кісткове борошно: підживлює колонію і дає їй очки ----------

    @Override
    public boolean isFertilizable(WorldView world, BlockPos pos, BlockState state, boolean isClient) {
        return true;
    }

    @Override
    public boolean canGrow(World world, Random random, BlockPos pos, BlockState state) {
        return true;
    }

    @Override
    public void grow(ServerWorld world, Random random, BlockPos pos, BlockState state) {
        int vigor = Math.min(MAX_VIGOR, state.get(VIGOR) + 3);
        world.setBlockState(pos, state.with(VIGOR, vigor), Block.NOTIFY_LISTENERS);
        ColonyData data = ColonyData.get(world);
        String key = ColonyData.key(species(), pos);
        data.addPoints(key, 5);
        spread(world, pos, vigor, random, data, key, data.colony(key).levels[ColonyData.AGGRESSION]);
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
