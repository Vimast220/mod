package com.realshrooms;

import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Дріжжі: швидка їжа для грибниці. ПКМ по блоку грибниці дає колонії очки й силу.
 * Багата колонія (40+ очок) дріжджі не потребує. Їх також печуть із пшеницею на заквасці.
 */
public class YeastItem extends Item {
    public YeastItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        World world = context.getWorld();
        BlockPos pos = context.getBlockPos();
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof MyceliumBlock mycelium)) {
            return super.useOnBlock(context);
        }
        if (world instanceof ServerWorld serverWorld) {
            ColonyData data = ColonyData.get(serverWorld);
            String key = ColonyData.key(mycelium.species(), pos);
            if (data.colony(key).points >= 40) {
                return ActionResult.FAIL;
            }
            data.addPoints(key, 4);
            serverWorld.setBlockState(pos, state.with(MyceliumBlock.VIGOR, Math.min(MyceliumBlock.MAX_VIGOR, state.get(MyceliumBlock.VIGOR) + 2)),
                2);
            serverWorld.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
                pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5, 6, 0.3, 0.1, 0.3, 0.0);
            PlayerEntity player = context.getPlayer();
            if (player == null || !player.getAbilities().creativeMode) {
                context.getStack().decrement(1);
            }
        }
        return ActionResult.success(world.isClient);
    }
}
