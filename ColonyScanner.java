package com.realshrooms;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Рахує РЕАЛЬНИЙ розмір колонії: заливає по з'єднаних блоках грибниці одного виду (як 26-сусідство),
 * лише в завантажених чанках. Це те, що колонія справді займає у світі, на відміну від числа в пам'яті.
 */
public final class ColonyScanner {
    private ColonyScanner() {
    }

    public static final class Result {
        public String species = "";
        public int count;
        public int cores;
        public int nodes;
        public int hyphae;
        /** true, якщо підрахунок обірвано через ліміт (колонія більша). */
        public boolean truncated;
        public final Map<Host, Integer> hosts = new EnumMap<>(Host.class);
        public final List<BlockPos> positions = new ArrayList<>();
        public final List<MyceliumBlock.Part> parts = new ArrayList<>();
        public final List<BlockPos> coreBlocks = new ArrayList<>();
    }

    /** Скануємо колонію від блока start. Повертає null, якщо там не блок грибниці. */
    public static Result scan(ServerWorld world, BlockPos start, int limit, boolean collect) {
        if (!(world.getBlockState(start).getBlock() instanceof MyceliumBlock root)) {
            return null;
        }
        String species = root.species();
        Result result = new Result();
        result.species = species;

        Set<Long> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start.toImmutable());
        seen.add(start.asLong());

        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            BlockState state = world.getBlockState(pos);
            if (!(state.getBlock() instanceof MyceliumBlock block)) {
                continue;
            }
            result.count++;
            switch (block.part()) {
                case CORE -> {
                    result.cores++;
                    result.coreBlocks.add(pos);
                }
                case NODE -> result.nodes++;
                case HYPHA -> result.hyphae++;
            }
            result.hosts.merge(state.get(MyceliumBlock.HOST), 1, Integer::sum);
            if (collect) {
                result.positions.add(pos);
                result.parts.add(block.part());
            }
            if (result.count >= limit) {
                result.truncated = !queue.isEmpty();
                break;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos next = pos.add(dx, dy, dz);
                        if (!world.getChunkManager().isChunkLoaded(next.getX() >> 4, next.getZ() >> 4)) {
                            continue;
                        }
                        if (seen.add(next.asLong())
                            && world.getBlockState(next).getBlock() instanceof MyceliumBlock other
                            && other.species().equals(species)) {
                            queue.add(next.toImmutable());
                        }
                    }
                }
            }
        }
        return result;
    }
}
