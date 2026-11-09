package com.realshrooms;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.StringIdentifiable;

/**
 * «Хазяїн»: який блок світу захопила грибниця. Блок грибниці виглядає як хазяїн, вкритий нитками,
 * а коли грибниця гине, на його місці знову з'являється оригінал.
 */
public enum Host implements StringIdentifiable {
    DIRT("dirt"),
    STONE("stone"),
    DEEPSLATE("deepslate"),
    SAND("sand"),
    GRAVEL("gravel"),
    WOOD("wood");

    private final String id;

    Host(String id) {
        this.id = id;
    }

    @Override
    public String asString() {
        return this.id;
    }

    /** Який хазяїн у цього блока, або null, якщо блок захопити не можна. */
    public static Host of(BlockState state) {
        if (state.isOf(Blocks.GRAVEL)) {
            return GRAVEL;
        }
        if (state.isOf(Blocks.DEEPSLATE)) {
            return DEEPSLATE;
        }
        if (state.isIn(BlockTags.SAND)) {
            return SAND;
        }
        if (state.isIn(BlockTags.BASE_STONE_OVERWORLD)) {
            return STONE;
        }
        if (state.isIn(BlockTags.LOGS)) {
            return WOOD;
        }
        if (state.isIn(BlockTags.DIRT)) {
            return DIRT;
        }
        return null;
    }

    /** Що лишається на місці блока, коли грибниця гине. Деревина зотліває без сліду. */
    public BlockState original() {
        return switch (this) {
            case DIRT -> Blocks.DIRT.getDefaultState();
            case STONE -> Blocks.STONE.getDefaultState();
            case DEEPSLATE -> Blocks.DEEPSLATE.getDefaultState();
            case SAND -> Blocks.SAND.getDefaultState();
            case GRAVEL -> Blocks.GRAVEL.getDefaultState();
            case WOOD -> Blocks.AIR.getDefaultState();
        };
    }
}
