package com.realshrooms;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

/**
 * Режим відладки: команда /realshrooms (потрібен рівень доступу 2, тобто оператор або чит-режим).
 *
 *   select          вибрати колонію, на яку дивишся (блок під прицілом)
 *   size            РЕАЛЬНИЙ розмір вибраної колонії (серце, вузли, гіфи, хазяїни) і дані з пам'яті
 *   recount         записати реальний розмір у пам'ять колонії
 *   debug on|off    підсвічування вибраної колонії частинками й рядок стану (серце: білі, вузли: вогонь, гіфи: зелені)
 *   info            детально про колонію під прицілом, включно з їжею області
 *   points add|set  <n>
 *   upgrade <name> <0-3>   виставити рівень апгрейда (expansion, fruiting, resilience, aggression)
 *   tick <n>        прискорено «прокрутити» n випадкових тіків для всіх блоків вибраної колонії
 *   killcore        вбити головну грибницю вибраної колонії
 *   list            відомі колонії (з пам'яті): очки, розмір, серце
 *   food reset      скинути поживні запаси областей
 */
public final class DebugCommands {
    private DebugCommands() {
    }

    private static final class Selection {
        BlockPos anchor;
        List<BlockPos> positions = new ArrayList<>();
        List<MyceliumBlock.Part> parts = new ArrayList<>();

        Selection(BlockPos anchor) {
            this.anchor = anchor;
        }
    }

    private static final Map<UUID, Selection> SELECTED = new HashMap<>();
    private static final Set<UUID> DEBUG = new HashSet<>();

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> build(dispatcher));
        ServerTickEvents.END_SERVER_TICK.register(DebugCommands::tick);
    }

    private static void build(CommandDispatcher<ServerCommandSource> dispatcher) {
        LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal("realshrooms")
            .requires(source -> source.hasPermissionLevel(2));

        root.then(CommandManager.literal("select").executes(DebugCommands::select));
        root.then(CommandManager.literal("size").executes(DebugCommands::size));
        root.then(CommandManager.literal("info").executes(DebugCommands::info));
        root.then(CommandManager.literal("recount").executes(DebugCommands::recount));
        root.then(CommandManager.literal("killcore").executes(DebugCommands::killCore));
        root.then(CommandManager.literal("list").executes(DebugCommands::list));
        root.then(CommandManager.literal("debug")
            .then(CommandManager.literal("on").executes(ctx -> setDebug(ctx, true)))
            .then(CommandManager.literal("off").executes(ctx -> setDebug(ctx, false))));
        root.then(CommandManager.literal("points")
            .then(CommandManager.literal("add")
                .then(CommandManager.argument("n", IntegerArgumentType.integer(0, 500)).executes(ctx -> points(ctx, true))))
            .then(CommandManager.literal("set")
                .then(CommandManager.argument("n", IntegerArgumentType.integer(0, 500)).executes(ctx -> points(ctx, false)))));
        root.then(CommandManager.literal("tick")
            .then(CommandManager.argument("n", IntegerArgumentType.integer(1, 300)).executes(DebugCommands::fastForward)));
        root.then(CommandManager.literal("food").then(CommandManager.literal("reset").executes(ctx -> {
            ColonyData.get(ctx.getSource().getWorld()).resetFood();
            feedback(ctx.getSource(), "Поживні запаси областей скинуто.");
            return 1;
        })));

        LiteralArgumentBuilder<ServerCommandSource> upgrade = CommandManager.literal("upgrade");
        for (int i = 0; i < ColonyData.UPGRADE_NAMES.length; i++) {
            final int index = i;
            upgrade.then(CommandManager.literal(ColonyData.UPGRADE_NAMES[i])
                .then(CommandManager.argument("level", IntegerArgumentType.integer(0, ColonyData.MAX_LEVEL))
                    .executes(ctx -> setUpgrade(ctx, index))));
        }
        root.then(upgrade);

        dispatcher.register(root);
    }

    // ---------------------------------------------------------------- допоміжне

    private static void feedback(ServerCommandSource source, String message) {
        source.sendFeedback(() -> Text.literal(message), false);
    }

    private static void fail(ServerCommandSource source, String message) {
        source.sendError(Text.literal(message));
    }

    private static BlockPos lookedAt(ServerPlayerEntity player) {
        HitResult hit = player.raycast(48.0, 0.0f, false);
        if (hit.getType() == HitResult.Type.BLOCK) {
            return ((BlockHitResult) hit).getBlockPos();
        }
        return null;
    }

    /** Вибрана колонія гравця, а якщо немає, то блок грибниці під прицілом. */
    private static BlockPos target(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        ServerWorld world = ctx.getSource().getWorld();
        Selection selection = SELECTED.get(player.getUuid());
        if (selection != null && world.getBlockState(selection.anchor).getBlock() instanceof MyceliumBlock) {
            return selection.anchor;
        }
        BlockPos looked = lookedAt(player);
        if (looked != null && world.getBlockState(looked).getBlock() instanceof MyceliumBlock) {
            return looked;
        }
        fail(ctx.getSource(), "Колонію не вибрано: наведи приціл на блок грибниці й виконай /realshrooms select.");
        return null;
    }

    private static String partsText(ColonyScanner.Result r) {
        return "серце " + r.cores + ", вузлів " + r.nodes + ", гіф " + r.hyphae;
    }

    private static String hostsText(ColonyScanner.Result r) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<Host, Integer> entry : r.hosts.entrySet()) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(entry.getKey().asString()).append(' ').append(entry.getValue());
        }
        return text.toString();
    }

    private static void report(ServerCommandSource source, ServerWorld world, BlockPos anchor) {
        ColonyScanner.Result r = ColonyScanner.scan(world, anchor, 5000, false);
        if (r == null) {
            fail(source, "Це не блок грибниці.");
            return;
        }
        ColonyData data = ColonyData.get(world);
        ColonyData.Colony colony = data.colony(ColonyData.key(r.species, anchor));
        int[] levels = data.effectiveLevels(r.species, anchor);
        boolean hasCore = data.effectiveHasCore(r.species, anchor);
        feedback(source, "§eКолонія «" + r.species + "», область " + ColonyData.regionKey(anchor));
        feedback(source, "§aРеальний розмір: " + r.count + (r.truncated ? "+ (обрізано)" : "") + " блоків (" + partsText(r) + ")");
        feedback(source, "§7Хазяї: " + hostsText(r));
        feedback(source, "§7У пам'яті: розмір " + colony.size + ", очки " + colony.points + ", серце " + (hasCore ? "є" : "немає")
            + ", рівні Е" + levels[0] + " П" + levels[1] + " С" + levels[2] + " А" + levels[3]);
        source.sendFeedback(() -> Text.literal("§7Стратегія: ").append(
            Text.translatable("strategy.realshrooms." + ColonyData.strategy(colony, hasCore))), false);
    }

    // ---------------------------------------------------------------- команди

    private static int select(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        ServerWorld world = ctx.getSource().getWorld();
        BlockPos looked = lookedAt(player);
        if (looked == null || !(world.getBlockState(looked).getBlock() instanceof MyceliumBlock)) {
            fail(ctx.getSource(), "Наведи приціл на блок грибниці.");
            return 0;
        }
        SELECTED.put(player.getUuid(), new Selection(looked.toImmutable()));
        report(ctx.getSource(), world, looked);
        feedback(ctx.getSource(), "§bКолонію вибрано. /realshrooms debug on підсвітить її.");
        return 1;
    }

    private static int size(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        BlockPos anchor = target(ctx);
        if (anchor == null) {
            return 0;
        }
        report(ctx.getSource(), ctx.getSource().getWorld(), anchor);
        return 1;
    }

    private static int info(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        ServerWorld world = ctx.getSource().getWorld();
        BlockPos looked = lookedAt(player);
        if (looked == null || !(world.getBlockState(looked).getBlock() instanceof MyceliumBlock block)) {
            fail(ctx.getSource(), "Наведи приціл на блок грибниці.");
            return 0;
        }
        BlockState state = world.getBlockState(looked);
        feedback(ctx.getSource(), "§eБлок: " + block.part().id() + ", сила " + state.get(MyceliumBlock.VIGOR)
            + ", хазяїн " + state.get(MyceliumBlock.HOST).asString());
        report(ctx.getSource(), world, looked);
        return 1;
    }

    private static int recount(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        BlockPos anchor = target(ctx);
        if (anchor == null) {
            return 0;
        }
        ServerWorld world = ctx.getSource().getWorld();
        ColonyScanner.Result r = ColonyScanner.scan(world, anchor, 5000, false);
        ColonyData data = ColonyData.get(world);
        data.colony(ColonyData.key(r.species, anchor)).size = r.count;
        data.markDirty();
        feedback(ctx.getSource(), "Розмір колонії в пам'яті виправлено на " + r.count + ".");
        return 1;
    }

    private static int setDebug(CommandContext<ServerCommandSource> ctx, boolean on) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        if (on) {
            DEBUG.add(player.getUuid());
        } else {
            DEBUG.remove(player.getUuid());
        }
        feedback(ctx.getSource(), on ? "Відладку увімкнено: серце білі, вузли вогняні, гіфи зелені частинки (колонія має бути вибрана)."
            : "Відладку вимкнено.");
        return 1;
    }

    private static int points(CommandContext<ServerCommandSource> ctx, boolean add) throws CommandSyntaxException {
        BlockPos anchor = target(ctx);
        if (anchor == null) {
            return 0;
        }
        ServerWorld world = ctx.getSource().getWorld();
        ColonyScanner.Result r = ColonyScanner.scan(world, anchor, 1, false);
        ColonyData data = ColonyData.get(world);
        String key = ColonyData.key(r.species, anchor);
        int n = IntegerArgumentType.getInteger(ctx, "n");
        ColonyData.Colony colony = data.colony(key);
        colony.points = add ? Math.min(ColonyData.MAX_POINTS, colony.points + n) : Math.min(ColonyData.MAX_POINTS, n);
        data.markDirty();
        feedback(ctx.getSource(), "Очки колонії: " + colony.points);
        return 1;
    }

    private static int setUpgrade(CommandContext<ServerCommandSource> ctx, int index) throws CommandSyntaxException {
        BlockPos anchor = target(ctx);
        if (anchor == null) {
            return 0;
        }
        ServerWorld world = ctx.getSource().getWorld();
        ColonyScanner.Result r = ColonyScanner.scan(world, anchor, 1, false);
        ColonyData data = ColonyData.get(world);
        data.colony(ColonyData.key(r.species, anchor)).levels[index] = IntegerArgumentType.getInteger(ctx, "level");
        data.markDirty();
        feedback(ctx.getSource(), "Апгрейд " + ColonyData.UPGRADE_NAMES[index] + " = " + IntegerArgumentType.getInteger(ctx, "level"));
        return 1;
    }

    private static int fastForward(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        BlockPos anchor = target(ctx);
        if (anchor == null) {
            return 0;
        }
        ServerWorld world = ctx.getSource().getWorld();
        int rounds = IntegerArgumentType.getInteger(ctx, "n");
        int ticked = 0;
        for (int round = 0; round < rounds; round++) {
            ColonyScanner.Result r = ColonyScanner.scan(world, anchor, 1500, true);
            if (r == null) {
                break;
            }
            for (BlockPos pos : r.positions) {
                BlockState state = world.getBlockState(pos);
                if (state.getBlock() instanceof MyceliumBlock) {
                    state.randomTick(world, pos, world.random);
                    ticked++;
                }
            }
        }
        feedback(ctx.getSource(), "Прокручено " + rounds + " раундів (" + ticked + " тіків блоків).");
        report(ctx.getSource(), world, anchor);
        return 1;
    }

    private static int killCore(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        BlockPos anchor = target(ctx);
        if (anchor == null) {
            return 0;
        }
        ServerWorld world = ctx.getSource().getWorld();
        ColonyScanner.Result r = ColonyScanner.scan(world, anchor, 5000, false);
        int killed = 0;
        for (BlockPos pos : r.coreBlocks) {
            BlockState state = world.getBlockState(pos);
            if (state.getBlock() instanceof MyceliumBlock block) {
                block.die(world, pos, state);
                killed++;
            }
        }
        feedback(ctx.getSource(), "Вбито головних грибниць: " + killed);
        return 1;
    }

    private static int list(CommandContext<ServerCommandSource> ctx) {
        ColonyData data = ColonyData.get(ctx.getSource().getWorld());
        List<Map.Entry<String, ColonyData.Colony>> entries = new ArrayList<>(data.all().entrySet());
        entries.removeIf(entry -> entry.getValue().points == 0 && entry.getValue().size == 0 && !entry.getValue().hasCore);
        entries.sort((a, b) -> Integer.compare(b.getValue().size, a.getValue().size));
        feedback(ctx.getSource(), "§eКолоній у пам'яті: " + entries.size() + " (показано до 15)");
        for (int i = 0; i < Math.min(15, entries.size()); i++) {
            ColonyData.Colony c = entries.get(i).getValue();
            feedback(ctx.getSource(), "§7" + entries.get(i).getKey() + ": розмір " + c.size + ", очки " + c.points
                + ", серце " + (c.hasCore ? "є" : "немає"));
        }
        return 1;
    }

    // ---------------------------------------------------------------- підсвічування

    private static void tick(MinecraftServer server) {
        if (DEBUG.isEmpty()) {
            return;
        }
        long time = server.getTicks();
        if (time % 10 != 0) {
            return;
        }
        for (UUID id : DEBUG) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
            Selection selection = SELECTED.get(id);
            if (player == null || selection == null) {
                continue;
            }
            ServerWorld world = player.getServerWorld();

            // Раз на 2 секунди перераховуємо колонію й оновлюємо рядок стану.
            if (time % 40 == 0) {
                ColonyScanner.Result r = ColonyScanner.scan(world, selection.anchor, 1500, true);
                if (r == null) {
                    selection.positions.clear();
                    selection.parts.clear();
                    player.sendMessage(Text.literal("§cВибрана колонія зникла (на місці якоря вже не грибниця)."), true);
                    continue;
                }
                selection.positions = r.positions;
                selection.parts = r.parts;
                ColonyData data = ColonyData.get(world);
                ColonyData.Colony colony = data.colony(ColonyData.key(r.species, selection.anchor));
                player.sendMessage(Text.literal("§e" + r.species + " §fреально " + r.count + (r.truncated ? "+" : "")
                    + " (" + partsText(r) + ") §7| очки " + colony.points + " | у пам'яті " + colony.size), true);
            }

            int spawned = 0;
            for (int i = 0; i < selection.positions.size() && spawned < 400; i++) {
                BlockPos pos = selection.positions.get(i);
                if (pos.getSquaredDistance(player.getBlockPos()) > 48 * 48) {
                    continue;
                }
                ParticleEffect particle = switch (selection.parts.get(i)) {
                    case CORE -> ParticleTypes.END_ROD;
                    case NODE -> ParticleTypes.FLAME;
                    case HYPHA -> ParticleTypes.COMPOSTER;
                };
                world.spawnParticles(player, particle, true, pos.getX() + 0.5, pos.getY() + 1.15, pos.getZ() + 0.5, 1, 0.0, 0.0, 0.0, 0.0);
                spawned++;
            }
        }
    }
}
