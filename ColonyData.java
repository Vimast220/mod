package com.realshrooms;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;

/**
 * Пам'ять колоній: очки, апгрейди, розмір, стан «мозку» і спільні запаси їжі областей. Зберігається зі світом.
 *
 * Колонія = грибниця одного виду в одній «області» світу 32x32 блоки.
 * Їжа області (дерева, вологий ґрунт) спільна для всіх видів, і колонії конкурують за неї.
 */
public class ColonyData extends PersistentState {
    public static final int EXPANSION = 0;   // Експансія: швидше захоплює, але гіфи нестабільніші
    public static final int FRUITING = 1;    // Плодоношення: частіше дає гриби, але вузли дорожчі
    public static final int RESILIENCE = 2;  // Стійкість: повільніше слабшає й краще захищається, але росте повільніше
    public static final int AGGRESSION = 3;  // Агресія: сильніша у війнах, отруює травоїдних, але дорожча в утриманні
    public static final String[] UPGRADE_NAMES = {"expansion", "fruiting", "resilience", "aggression"};

    public static final int MAX_LEVEL = 3;
    /** Усього рівнів апгрейдів на колонію (з 12): тож колонія спеціалізується, а не бере все. */
    public static final int MAX_TOTAL_LEVELS = 7;
    public static final int MAX_POINTS = 200;
    public static final int MAX_FOOD = 60;
    private static final int START_FOOD = 30;
    /** Ціна переходу на рівень 1, 2, 3. */
    private static final int[] COST = {12, 25, 45};

    public static class Colony {
        public int points;
        public final int[] levels = new int[4];
        /** Приблизна кількість блоків колонії. */
        public int size;
        /** Чи є в колонії головна грибниця («мозок»). */
        public boolean hasCore;
        /** Скільки ще «думок» колонія вважає, що на неї нападають. */
        public int threat;
        /** Скільки ще «думок» колонія вважає, що воює з сусідом. */
        public int contact;

        boolean isEmpty() {
            return this.points == 0 && this.size == 0 && !this.hasCore && this.threat == 0 && this.contact == 0
                && this.levels[0] == 0 && this.levels[1] == 0 && this.levels[2] == 0 && this.levels[3] == 0;
        }
    }

    private final Map<String, Colony> colonies = new HashMap<>();
    private final Map<String, Integer> food = new HashMap<>();

    public static ColonyData get(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(ColonyData::fromNbt, ColonyData::new, "realshrooms_colonies");
    }

    public static String key(String species, BlockPos pos) {
        return species + ":" + regionKey(pos);
    }

    public static String regionKey(BlockPos pos) {
        return (pos.getX() >> 5) + ":" + (pos.getZ() >> 5);
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

    public Colony peek(String key) {
        return this.colonies.get(key);
    }

    /** Усі відомі колонії (для відладки). */
    public Map<String, Colony> all() {
        return Collections.unmodifiableMap(this.colonies);
    }

    private static String[] neighbourKeys(String species, BlockPos pos) {
        int rx = pos.getX() >> 5;
        int rz = pos.getZ() >> 5;
        String[] keys = new String[9];
        int i = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                keys[i++] = species + ":" + (rx + dx) + ":" + (rz + dz);
            }
        }
        return keys;
    }

    /** Рівні апгрейдів, які діють у цій точці: найкращі серед своєї й сусідніх областей того ж виду. */
    public int[] effectiveLevels(String species, BlockPos pos) {
        int[] result = new int[4];
        for (String key : neighbourKeys(species, pos)) {
            Colony colony = this.colonies.get(key);
            if (colony != null) {
                for (int i = 0; i < 4; i++) {
                    result[i] = Math.max(result[i], colony.levels[i]);
                }
            }
        }
        return result;
    }

    /** Чи є головна грибниця в цій чи сусідніх областях (колонія не обривається на межі області). */
    public boolean effectiveHasCore(String species, BlockPos pos) {
        for (String key : neighbourKeys(species, pos)) {
            Colony colony = this.colonies.get(key);
            if (colony != null && colony.hasCore) {
                return true;
            }
        }
        return false;
    }

    /** Витрачає очки своєї області, а коли їх бракує, позичає в багатшої сусідньої області того ж виду. */
    public boolean spendShared(String species, BlockPos pos, int amount) {
        if (spend(key(species, pos), amount)) {
            return true;
        }
        String own = key(species, pos);
        for (String key : neighbourKeys(species, pos)) {
            if (key.equals(own)) {
                continue;
            }
            Colony other = this.colonies.get(key);
            if (other != null && other.points >= amount + 3) {
                other.points -= amount;
                this.markDirty();
                return true;
            }
        }
        return false;
    }

    public void resetFood() {
        this.food.clear();
        this.markDirty();
    }

    /** Бере одиницю з поживного запасу області. Повертає false, якщо запас вичерпано. */
    public boolean takeFood(String regionKey) {
        int amount = this.food.getOrDefault(regionKey, START_FOOD);
        if (amount <= 0) {
            return false;
        }
        this.food.put(regionKey, amount - 1);
        this.markDirty();
        return true;
    }

    /** Запаси областей повільно відновлюються (дерева, дощ, відмерла органіка). */
    public void regenFood() {
        if (this.food.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Integer> entry : this.food.entrySet()) {
            entry.setValue(Math.min(MAX_FOOD, entry.getValue() + 1));
        }
        this.food.values().removeIf(value -> value >= START_FOOD);
        this.markDirty();
    }

    /** Купує перший доступний апгрейд за пріоритетом. Повертає номер апгрейда або -1. */
    public int tryUpgrade(String key, int[] priority) {
        Colony colony = colony(key);
        if (colony.levels[0] + colony.levels[1] + colony.levels[2] + colony.levels[3] >= MAX_TOTAL_LEVELS) {
            return -1;
        }
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

    /** «Характер» виду: у якому порядку колонія зазвичай вкладає очки в апгрейди. */
    public static int[] priority(String species) {
        return switch (species) {
            case "chanterelle" -> new int[]{RESILIENCE, FRUITING, EXPANSION, AGGRESSION};
            case "porcini" -> new int[]{FRUITING, RESILIENCE, EXPANSION, AGGRESSION};
            case "morel", "inky_cap" -> new int[]{EXPANSION, FRUITING, RESILIENCE, AGGRESSION};
            case "fly_agaric", "red_mushroom", "honey_fungus" -> new int[]{AGGRESSION, EXPANSION, RESILIENCE, FRUITING};
            case "death_cap" -> new int[]{AGGRESSION, RESILIENCE, FRUITING, EXPANSION};
            case "mycena_chlorophos" -> new int[]{FRUITING, EXPANSION, RESILIENCE, AGGRESSION};
            case "gibellula" -> new int[]{AGGRESSION, RESILIENCE, EXPANSION, FRUITING};
            case "penicillium" -> new int[]{EXPANSION, AGGRESSION, FRUITING, RESILIENCE};
            case "brown_mushroom" -> new int[]{EXPANSION, RESILIENCE, FRUITING, AGGRESSION};
            default -> new int[]{EXPANSION, FRUITING, RESILIENCE, AGGRESSION};
        };
    }

    /** Розумний пріоритет: під загрозою колонія кується в стійкість, у війні в агресію, інакше за характером. */
    public static int[] dynamicPriority(String species, Colony colony) {
        Set<Integer> order = new LinkedHashSet<>();
        if (colony.threat > 0) {
            order.add(RESILIENCE);
        }
        if (colony.contact > 0) {
            order.add(AGGRESSION);
        }
        for (int upgrade : priority(species)) {
            order.add(upgrade);
        }
        int[] result = new int[order.size()];
        int i = 0;
        for (int upgrade : order) {
            result[i++] = upgrade;
        }
        return result;
    }

    /** Поточна стратегія колонії (ключ для перекладу strategy.realshrooms.*). */
    public static String strategy(Colony colony, boolean hasCore) {
        if (!hasCore) {
            return "headless";
        }
        if (colony.points <= 1) {
            return "hungry";
        }
        if (colony.threat > 0) {
            return "defend";
        }
        if (colony.contact > 0) {
            return "war";
        }
        if (colony.size < 25 || colony.points >= 12) {
            return "expand";
        }
        return "stable";
    }

    public static ColonyData fromNbt(NbtCompound nbt) {
        ColonyData data = new ColonyData();
        NbtCompound all = nbt.getCompound("colonies");
        for (String key : all.getKeys()) {
            NbtCompound tag = all.getCompound(key);
            Colony colony = new Colony();
            colony.points = tag.getInt("points");
            colony.size = tag.getInt("size");
            colony.hasCore = tag.getBoolean("core");
            colony.threat = tag.getInt("threat");
            colony.contact = tag.getInt("contact");
            int[] levels = tag.getIntArray("levels");
            for (int i = 0; i < Math.min(4, levels.length); i++) {
                colony.levels[i] = levels[i];
            }
            data.colonies.put(key, colony);
        }
        NbtCompound foodTag = nbt.getCompound("food");
        for (String key : foodTag.getKeys()) {
            data.food.put(key, foodTag.getInt(key));
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
            tag.putInt("size", colony.size);
            tag.putBoolean("core", colony.hasCore);
            tag.putInt("threat", colony.threat);
            tag.putInt("contact", colony.contact);
            tag.putIntArray("levels", colony.levels);
            all.put(entry.getKey(), tag);
        }
        nbt.put("colonies", all);
        NbtCompound foodTag = new NbtCompound();
        for (Map.Entry<String, Integer> entry : this.food.entrySet()) {
            foodTag.putInt(entry.getKey(), entry.getValue());
        }
        nbt.put("food", foodTag);
        return nbt;
    }
}
