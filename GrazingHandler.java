package com.realshrooms;

import java.util.HashSet;
import java.util.Set;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.entity.passive.GoatEntity;
import net.minecraft.entity.passive.RabbitEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * Травоїдні тварини (корови, вівці, кози, кролики, коні, лами) шукають і їдять гриби та грибницю.
 *  - їдять і наші гриби, і ванільні (коричневий, червоний);
 *  - об'їдають грибницю: вона слабшає (стійкість колонії зменшує шкоду);
 *  - отруйні гриби й агресивна грибниця отруюють тварину;
 *  - їжа лікує тварину.
 * Тварини «працюють» лише поблизу гравців (радіус 24 блоки).
 */
public final class GrazingHandler {
    private GrazingHandler() {
    }

    public static void register() {
        ServerTickEvents.END_WORLD_TICK.register(GrazingHandler::tick);
    }

    private static void tick(ServerWorld world) {
        if (!ShroomConfig.animalsGraze || world.getTime() % 20 != 0) {
            return;
        }
        Set<AnimalEntity> seen = new HashSet<>();
        for (ServerPlayerEntity player : world.getPlayers()) {
            Box box = player.getBoundingBox().expand(24.0);
            for (AnimalEntity animal : world.getEntitiesByClass(AnimalEntity.class, box, GrazingHandler::isHerbivore)) {
                if (seen.add(animal)) {
                    graze(world, animal);
                }
            }
        }
    }

    private static boolean isHerbivore(AnimalEntity animal) {
        return animal instanceof CowEntity
            || animal instanceof SheepEntity
            || animal instanceof GoatEntity
            || animal instanceof RabbitEntity
            || animal instanceof AbstractHorseEntity;
    }

    private static boolean isFood(BlockState state) {
        return state.getBlock() instanceof ShroomBlock
            || state.getBlock() instanceof MyceliumBlock
            || state.isOf(Blocks.BROWN_MUSHROOM)
            || state.isOf(Blocks.RED_MUSHROOM);
    }

    private static void graze(ServerWorld world, AnimalEntity animal) {
        // Не кожну секунду: тварина ще й просто гуляє.
        if (animal.getRandom().nextInt(3) != 0) {
            return;
        }
        BlockPos origin = animal.getBlockPos();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(origin.add(-5, -1, -5), origin.add(5, 1, 5))) {
            if (isFood(world.getBlockState(pos))) {
                double distance = pos.getSquaredDistance(origin);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = pos.toImmutable();
                }
            }
        }
        if (best == null) {
            return;
        }
        if (bestDistance <= 2.5) {
            eat(world, animal, best);
        } else {
            animal.getNavigation().startMovingTo(best.getX() + 0.5, best.getY(), best.getZ() + 0.5, 1.0);
        }
    }

    private static void eat(ServerWorld world, AnimalEntity animal, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        boolean poisonous = false;

        if (block instanceof MyceliumBlock mycelium) {
            ColonyData.Colony colony = ColonyData.get(world).colony(ColonyData.key(mycelium.species(), pos));
            int damage = Math.max(1, 3 - colony.levels[ColonyData.RESILIENCE]);
            int vigor = state.get(MyceliumBlock.VIGOR);
            if (vigor <= 0) {
                mycelium.die(world, pos, state);
            } else {
                world.setBlockState(pos, state.with(MyceliumBlock.VIGOR, Math.max(0, vigor - damage)), Block.NOTIFY_LISTENERS);
            }
            poisonous = colony.levels[ColonyData.AGGRESSION] > 0 || isToxic(mycelium.species());
        } else if (block instanceof ShroomBlock) {
            world.breakBlock(pos, false);
            poisonous = isToxic(Registries.BLOCK.getId(block).getPath());
        } else {
            // ванільні гриби
            world.breakBlock(pos, false);
        }

        animal.heal(2.0f);
        animal.playSound(SoundEvents.ENTITY_GENERIC_EAT, 1.0f, 1.0f);
        if (poisonous) {
            animal.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, 100));
        }
    }

    private static boolean isToxic(String species) {
        return species.equals("fly_agaric") || species.equals("death_cap") || species.equals("morel");
    }
}
