package com.realshrooms;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.StringIdentifiable;

/**
 * «Хазяїн»: який блок світу захопила грибниця. Блок грибниці виглядає як хазяїн, вкритий нитками,
 * а коли грибниця гине, на його місці знову з'являється оригінал (деревина зотліває без сліду).
 */
public enum Host implements StringIdentifiable {
    DIRT("dirt", Kind.SOIL),
    GRASS("grass", Kind.SOIL),
    PODZOL("podzol", Kind.SOIL),
    MOSS("moss", Kind.SOIL),
    STONE("stone", Kind.ROCK),
    DEEPSLATE("deepslate", Kind.DEEP),
    SAND("sand", Kind.LOOSE),
    GRAVEL("gravel", Kind.LOOSE),
    OAK("oak", Kind.WOOD),
    SPRUCE("spruce", Kind.WOOD),
    BIRCH("birch", Kind.WOOD),
    JUNGLE("jungle", Kind.WOOD),
    ACACIA("acacia", Kind.WOOD),
    DARK_OAK("dark_oak", Kind.WOOD),
    MANGROVE("mangrove", Kind.WOOD),
    CHERRY("cherry", Kind.WOOD),
    HAY("hay", Kind.ORGANIC);

    /** Категорія хазяїна: від неї залежить, хто й за скільки може його освоїти. */
    public enum Kind { SOIL, ROCK, DEEP, LOOSE, WOOD, ORGANIC }

    private final String id;
    private final Kind kind;

    Host(String id, Kind kind) {
        this.id = id;
        this.kind = kind;
    }

    @Override
    public String asString() {
        return this.id;
    }

    public Kind kind() {
        return this.kind;
    }

    /** Який хазяїн у цього блока, або null, якщо блок захопити не можна. */
    public static Host of(BlockState state) {
        if (state.getBlock() instanceof MyceliumBlock || state.getBlock() instanceof SubstrateBlock) {
            return null;
        }
        if (state.isOf(Blocks.HAY_BLOCK)) {
            return HAY;
        }
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
        if (state.isIn(BlockTags.OAK_LOGS)) {
            return OAK;
        }
        if (state.isIn(BlockTags.SPRUCE_LOGS)) {
            return SPRUCE;
        }
        if (state.isIn(BlockTags.BIRCH_LOGS)) {
            return BIRCH;
        }
        if (state.isIn(BlockTags.JUNGLE_LOGS)) {
            return JUNGLE;
        }
        if (state.isIn(BlockTags.ACACIA_LOGS)) {
            return ACACIA;
        }
        if (state.isIn(BlockTags.DARK_OAK_LOGS)) {
            return DARK_OAK;
        }
        if (state.isIn(BlockTags.MANGROVE_LOGS)) {
            return MANGROVE;
        }
        if (state.isIn(BlockTags.CHERRY_LOGS)) {
            return CHERRY;
        }
        if (state.isIn(BlockTags.LOGS)) {
            return OAK;
        }
        if (state.isOf(Blocks.GRASS_BLOCK)) {
            return GRASS;
        }
        if (state.isOf(Blocks.PODZOL)) {
            return PODZOL;
        }
        if (state.isOf(Blocks.MOSS_BLOCK)) {
            return MOSS;
        }
        if (state.isIn(BlockTags.DIRT)) {
            return DIRT;
        }
        return null;
    }

    /** Що лишається на місці блока, коли грибниця гине. */
    public BlockState original() {
        return switch (this) {
            case DIRT -> Blocks.DIRT.getDefaultState();
            case GRASS -> Blocks.GRASS_BLOCK.getDefaultState();
            case PODZOL -> Blocks.PODZOL.getDefaultState();
            case MOSS -> Blocks.MOSS_BLOCK.getDefaultState();
            case STONE -> Blocks.STONE.getDefaultState();
            case DEEPSLATE -> Blocks.DEEPSLATE.getDefaultState();
            case SAND -> Blocks.SAND.getDefaultState();
            case GRAVEL -> Blocks.GRAVEL.getDefaultState();
            case HAY -> Blocks.HAY_BLOCK.getDefaultState();
            case OAK, SPRUCE, BIRCH, JUNGLE, ACACIA, DARK_OAK, MANGROVE, CHERRY -> Blocks.AIR.getDefaultState();
        };
    }
}
