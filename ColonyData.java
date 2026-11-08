package com.realshrooms;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;

/**
 * «Мозок» колоній: пул очок і рівні апгрейдів, які зберігаються разом зі світом.
 *
 * Колонія = грибниця одного виду в одній «області» світу 32x32 блоки.
 * Очки колонія заробляє, коли її годують (плодове тіло, коріння, поглинені рослини),
 * а витрачає на ріст (захоплення блоків), плодоношення й апгрейди.
 */
public class ColonyData extends PersistentState {
    public static final int EXPANSION = 0;   // Експансія: швидше захоплює ґрунт
    public static final int FRUITING = 1;    // Плодоношення: частіше й ширше дає гриби
    public static final int RESILIENCE = 2;  // Стійкість: повільніше слабшає, краще захищається
    public static final int AGGRESSION = 3;  // Агресія: сильніша у війнах, отруює травоїдних
    public static final String[] UPGRADE_NAMES = {"expansion", "fruiting", "resilience", "aggression"};

    public static final int MAX_LEVEL = 3;
    public static final int MAX_POINTS = 200;
    /** Ціна переходу на рівень 1, 2, 3. */
    private static final int[] COST = {12, 25, 45};

    public static class Colony {
        public int points;
        public final int[] levels = new int[4];

        boolean isEmpty() {
            return this.points == 0 && this.levels[0] == 0 && this.levels[1] == 0
                && this.levels[2] == 0 && this.levels[3] == 0;
        }
    }

    private final Map<String, Colony> colonies = new HashMap<>();

    public static ColonyData get(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(ColonyData::fromNbt, ColonyData::new, "realshrooms_colonies");
    }

    public static String key(String species, BlockPos pos) {
        return species + ":" + (pos.getX() >> 5) + ":" + (pos.getZ() >> 5);
    }

    public Colony colony(String key) {
        return this.colonies.computeIfAbsent(key, k -> new Colony());
    }

    public void addPoints(String key, int amount) {
        Colony colony = colony(key);
        colony.points = Math.min(MAX_POINTS, colony.points + amount);
        this.markDirty();
    }

    /** Витрачає очки, якщо вони є. Повертає true, якщо вдалося. */
    public boolean spend(String key, int amount) {
        Colony colony = colony(key);
        if (colony.points < amount) {
            return false;
        }
        colony.points -= amount;
        this.markDirty();
        return true;
    }

    /** Купує перший доступний апгрейд за пріоритетом виду. Повертає номер апгрейда або -1. */
    public int tryUpgrade(String key, int[] priority) {
        Colony colony = colony(key);
        for (int upgrade : priority) {
            int level = colony.levels[upgrade];
            if (level < MAX_LEVEL && colony.points >= COST[level]) {
                colony.points -= COST[level];
                colony.levels[upgrade] = level + 1;
                this.markDirty();
                return upgrade;
            }
        }
        return -1;
    }

    /** «Характер» виду: у якому порядку колонія вкладає очки в апгрейди. */
    public static int[] priority(String species) {
        return switch (species) {
            case "chanterelle" -> new int[]{RESILIENCE, FRUITING, EXPANSION, AGGRESSION};
            case "porcini" -> new int[]{FRUITING, RESILIENCE, EXPANSION, AGGRESSION};
            case "morel", "inky_cap" -> new int[]{EXPANSION, FRUITING, RESILIENCE, AGGRESSION};
            case "fly_agaric", "red_mushroom" -> new int[]{AGGRESSION, EXPANSION, RESILIENCE, FRUITING};
            case "death_cap" -> new int[]{AGGRESSION, RESILIENCE, FRUITING, EXPANSION};
            case "mycena_chlorophos" -> new int[]{FRUITING, EXPANSION, RESILIENCE, AGGRESSION};
            case "brown_mushroom" -> new int[]{EXPANSION, RESILIENCE, FRUITING, AGGRESSION};
            default -> new int[]{EXPANSION, FRUITING, RESILIENCE, AGGRESSION};
        };
    }

    public static ColonyData fromNbt(NbtCompound nbt) {
        ColonyData data = new ColonyData();
        NbtCompound all = nbt.getCompound("colonies");
        for (String key : all.getKeys()) {
            NbtCompound tag = all.getCompound(key);
            Colony colony = new Colony();
            colony.points = tag.getInt("points");
            int[] levels = tag.getIntArray("levels");
            for (int i = 0; i < Math.min(4, levels.length); i++) {
                colony.levels[i] = levels[i];
            }
            data.colonies.put(key, colony);
        }
        return data;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        NbtCompound all = new NbtCompound();
        for (Map.Entry<String, Colony> entry : this.colonies.entrySet()) {
            Colony colony = entry.getValue();
            if (colony.isEmpty()) {
                continue;
            }
            NbtCompound tag = new NbtCompound();
            tag.putInt("points", colony.points);
            tag.putIntArray("levels", colony.levels);
            all.put(entry.getKey(), tag);
        }
        nbt.put("colonies", all);
        return nbt;
    }
}
