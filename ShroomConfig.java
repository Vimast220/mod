package com.realshrooms;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Налаштування балансу: файл config/realshrooms.json (створюється при першому запуску).
 * Усі числа можна міняти без перезбирання мода.
 */
public final class ShroomConfig {
    private ShroomConfig() {
    }

    /** Чи може грибниця захоплювати камінь і глибинний сланець. */
    public static boolean captureStone = true;
    /** Чи може грибниця захоплювати (зотліваючи) колоди дерев. */
    public static boolean captureWood = true;
    /** Чи може грибниця захоплювати пісок і гравій. */
    public static boolean captureSand = true;
    /** Війни між колоніями різних видів. */
    public static boolean warEnabled = true;
    /** Опеньок вбиває дерева. Якщо вимкнено, живе як звичайна грибниця на корінні. */
    public static boolean parasiteEnabled = true;
    /** Травоїдні їдять гриби й грибницю. */
    public static boolean animalsGraze = true;
    /** Множник швидкості захоплення (більше значить швидше). */
    public static double spreadSpeed = 1.0;
    /** Множник утримання колонії (більше значить дорожче). */
    public static double upkeepScale = 1.0;
    /** Множник тривалості життя гіф (більше значить довше). */
    public static double hyphaLifespan = 1.0;
    /** Скільки блоків максимум може мати одна колонія. */
    public static int maxColonyBlocks = 300;
    /** Раз на скільки тіків відновлюється поживний запас області (менше значить швидше). */
    public static int foodRegenTicks = 40;

    public static void load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("realshrooms.json");
        try {
            if (Files.exists(path)) {
                JsonObject json = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
                captureStone = bool(json, "captureStone", captureStone);
                captureWood = bool(json, "captureWood", captureWood);
                captureSand = bool(json, "captureSand", captureSand);
                warEnabled = bool(json, "warEnabled", warEnabled);
                parasiteEnabled = bool(json, "parasiteEnabled", parasiteEnabled);
                animalsGraze = bool(json, "animalsGraze", animalsGraze);
                spreadSpeed = number(json, "spreadSpeed", spreadSpeed);
                upkeepScale = number(json, "upkeepScale", upkeepScale);
                hyphaLifespan = number(json, "hyphaLifespan", hyphaLifespan);
                maxColonyBlocks = (int) number(json, "maxColonyBlocks", maxColonyBlocks);
                foodRegenTicks = Math.max(1, (int) number(json, "foodRegenTicks", foodRegenTicks));
            }
            save(path);
        } catch (IOException | RuntimeException e) {
            System.err.println("[realshrooms] Не вдалося прочитати конфіг, використано типові значення: " + e);
        }
    }

    private static void save(Path path) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("captureStone", captureStone);
        json.addProperty("captureWood", captureWood);
        json.addProperty("captureSand", captureSand);
        json.addProperty("warEnabled", warEnabled);
        json.addProperty("parasiteEnabled", parasiteEnabled);
        json.addProperty("animalsGraze", animalsGraze);
        json.addProperty("spreadSpeed", spreadSpeed);
        json.addProperty("upkeepScale", upkeepScale);
        json.addProperty("hyphaLifespan", hyphaLifespan);
        json.addProperty("maxColonyBlocks", maxColonyBlocks);
        json.addProperty("foodRegenTicks", foodRegenTicks);
        Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(json), StandardCharsets.UTF_8);
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        JsonElement element = json.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsBoolean() : fallback;
    }

    private static double number(JsonObject json, String key, double fallback) {
        JsonElement element = json.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsDouble() : fallback;
    }
}
