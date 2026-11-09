package com.realshrooms;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.Fertilizable;
import net.minecraft.block.SaplingBlock;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.SpiderEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;

/**
 * Грибниця: живий організм із трьох видів блоків (для кожного виду гриба):
 *
 *  - ГОЛОВНА (CORE): «мозок» колонії. Розвиває її (апгрейди), міцно захищена. Втративши її, колонія
 *    «безголова», і найсильніший вузол може стати новою головною.
 *  - ВУЗОЛ (NODE): стійкий вузол мережі. Передає силу, дає плодові тіла, збирає ресурси.
 *  - ГІФИ (HYPHA): найбільша частина. Досліджують нові місця, захоплюють блоки світу, збирають ресурси.
 *    Нестабільні, але, знайшовши ресурс (рослини, дерево, воду), ЗАКРІПЛЮЮТЬСЯ: стають вузлом.
 *
 * Блок зберігає «хазяїна» (HOST): захоплений блок світу, на який він схожий і який повертається, коли
 * грибниця гине. Сила (VIGOR 0..7) розтікається мережею (-1 за блок; гіфи не сильніші за 5).
 * Очки колонії (ColonyData) заробляються з їжі, витрачаються на утримання, захоплення, плодоношення,
 * війни й апгрейди; сусідні області одного виду діляться очками.
 */
public class MyceliumBlock extends Block implements Fertilizable {
    public enum Diet { ROOTS, PLANTS, PARASITE }

    public enum Part {
        CORE("core"), NODE("node"), HYPHA("hypha");

        private final String id;

        Part(String id) {
            this.id = id;
        }

        public String id() {
            return this.id;
        }
    }

    public static final int MAX_VIGOR = 7;
    public static final int HYPHA_MAX_VIGOR = 5;
    public static final IntProperty VIGOR = IntProperty.of("vigor", 0, MAX_VIGOR);
    public static final EnumProperty<Host> HOST = EnumProperty.of("host", Host.class);

    private static final Map<String, EnumMap<Part, MyceliumBlock>> BY_SPECIES = new HashMap<>();
    public static final List<MyceliumBlock> ALL = new ArrayList<>();

    private final Identifier mushroomId;
    private final Diet diet;
    private final boolean needsMoisture;
    private final Part part;

    public MyceliumBlock(Settings settings, Identifier mushroomId, Diet diet, boolean needsMoisture, Part part) {
        super(settings);
        this.mushroomId = mushroomId;
        this.diet = diet;
        this.needsMoisture = needsMoisture;
        this.part = part;
        this.setDefaultState(this.stateManager.getDefaultState().with(VIGOR, MAX_VIGOR).with(HOST, Host.DIRT));
    }

    public static void index(MyceliumBlock block) {
        BY_SPECIES.computeIfAbsent(block.species(), k -> new EnumMap<>(Part.class)).put(block.part, block);
        ALL.add(block);
    }

    public static MyceliumBlock of(String species, Part part) {
        EnumMap<Part, MyceliumBlock> parts = BY_SPECIES.get(species);
        return parts == null ? null : parts.get(part);
    }

    /** Назва виду, наприклад «chanterelle» або «brown_mushroom». */
    public String species() {
        return this.mushroomId.getPath();
    }

    public Part part() {
        return this.part;
    }

    private boolean isMold() {
        return species().equals("penicillium");
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(VIGOR, HOST);
    }

    // =====================================================================
    //  Заснування колонії
    // =====================================================================

    /** Дика колонія: стигле плодове тіло «заражає» ґрунт під собою. */
    public static void found(ServerWorld world, BlockPos pos, String species) {
        ColonyData data = ColonyData.get(world);
        String key = ColonyData.key(species, pos);
        ColonyData.Colony colony = data.colony(key);
        Part part = data.effectiveHasCore(species, pos) ? Part.NODE : Part.CORE;
        MyceliumBlock block = of(species, part);
        if (block == null) {
            return;
        }
        Host host = Host.of(world.getBlockState(pos));
        if (host == null) {
            host = Host.DIRT;
        }
        world.setBlockState(pos, block.getDefaultState().with(VIGOR, 5).with(HOST, host), Block.NOTIFY_LISTENERS);
        if (part == Part.CORE) {
            colony.hasCore = true;
        }
        colony.size++;
        colony.points = Math.max(colony.points, 8);
        data.markDirty();
    }

    /** Головну грибницю, яку поставив гравець, колонія отримує зі стартовим капіталом. */
    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack itemStack) {
        super.onPlaced(world, pos, state, placer, itemStack);
        if (this.part == Part.CORE && world instanceof ServerWorld serverWorld) {
            ColonyData data = ColonyData.get(serverWorld);
            ColonyData.Colony colony = data.colony(ColonyData.key(species(), pos));
            colony.hasCore = true;
            colony.size++;
            colony.points = Math.max(colony.points, 20);
            data.markDirty();
        }
    }

    // =====================================================================
    //  Життя блока
    // =====================================================================

    @Override
    public void randomTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        ColonyData data = ColonyData.get(world);
        String species = species();
        String key = ColonyData.key(species, pos);
        ColonyData.Colony colony = data.colony(key);
        // Рівні й «мозок» беруться з усієї округи: колонія не обривається на межі області.
        int[] levels = data.effectiveLevels(species, pos);
        int expansion = levels[ColonyData.EXPANSION];
        int fruiting = levels[ColonyData.FRUITING];
        int resilience = levels[ColonyData.RESILIENCE];
        int aggression = levels[ColonyData.AGGRESSION];
        boolean hasCore = data.effectiveHasCore(species, pos);

        boolean parasite = this.diet == Diet.PARASITE && ShroomConfig.parasiteEnabled;
        boolean treeDiet = this.diet != Diet.PLANTS;
        boolean wet = world.hasRain(pos.up()) || SubstrateBlock.hasWaterNearby(world, pos);
        boolean dormant = isDormant(world, pos);
        boolean hostOk = (!treeDiet || hasHostTree(world, pos)) && (!this.needsMoisture || wet);
        int vigor = state.get(VIGOR);

        // ---- 1. Живлення: звідки приходить сила й очки ----
        int earned = 0;
        int target = -1;
        if (hostOk) {
            boolean fed = this.part != Part.HYPHA && isFed(world, pos);
            if (fed) {
                target = MAX_VIGOR;
                earned += 1;
            }
            if (this.part == Part.CORE) {
                if (colony.points > 0) {
                    target = MAX_VIGOR;
                }
            } else if (this.part == Part.NODE && treeDiet) {
                target = Math.max(target, wet ? 4 : 2);
            }
            if (treeDiet && !parasite && this.part != Part.CORE && wet && random.nextInt(3) == 0
                && data.takeFood(ColonyData.regionKey(pos))) {
                // Цукри дерев: ресурс спільний для області, і колонії за нього конкурують.
                earned += 1;
            }
            if (!treeDiet && this.part != Part.CORE && graze(world, pos, random)) {
                vigor = Math.min(MAX_VIGOR, vigor + 3);
                target = MAX_VIGOR;
                earned += 2;
            }
            // Гібеллула: печерний гриб, паразит павуків.
            if (species.equals("gibellula") && this.part != Part.CORE) {
                List<SpiderEntity> spiders = world.getEntitiesByClass(SpiderEntity.class,
                    new Box(pos).expand(2.5), spider -> spider.isAlive());
                for (SpiderEntity spider : spiders) {
                    spider.damage(world.getDamageSources().magic(), 3.0f);
                    earned += spider.isAlive() ? 1 : 5;
                    target = MAX_VIGOR;
                }
            }
            for (BlockPos nearby : BlockPos.iterate(pos.add(-1, -1, -1), pos.add(1, 1, 1))) {
                if (nearby.equals(pos)) {
                    continue;
                }
                BlockState neighbour = world.getBlockState(nearby);
                if (neighbour.getBlock() instanceof MyceliumBlock other && other.species().equals(species)) {
                    target = Math.max(target, neighbour.get(VIGOR) - 1);
                }
            }
            if (this.part == Part.HYPHA) {
                target = Math.min(target, HYPHA_MAX_VIGOR);
            }
        }
        if (earned > 0) {
            data.addPoints(key, earned);
        }

        // ---- 2. Утримання: великий організм дорого коштує. Без очок блок слабшає. ----
        if (!dormant) {
            double base = switch (this.part) {
                case CORE -> 5.0;
                case NODE -> 4.0;
                case HYPHA -> 8.0;
            };
            base -= aggression * 0.5;
            if (this.part == Part.NODE) {
                base -= fruiting * 0.5;
            }
            if (isMold()) {
                base *= 1.5;   // пліснява невибаглива
            }
            int upkeep = (int) Math.max(2, Math.round(base / ShroomConfig.upkeepScale));
            if (random.nextInt(upkeep) == 0 && !data.spendShared(species, pos, 1)) {
                // Банкрутство: гіфи гинуть першими, вузли й серце лише слабшають.
                target = this.part == Part.HYPHA ? -1 : Math.min(target, 0);
            }
        }

        // ---- 3. Нестабільні гіфи: живуть недовго, особливо відрізані від мережі ----
        if (this.part == Part.HYPHA && !dormant) {
            double lifespan = 36 + 6 * resilience - 3 * expansion;
            if (isMold()) {
                lifespan *= 0.6;   // пліснява швидка й недовговічна
            }
            if (vigor <= 1) {
                lifespan /= 3.0;
            }
            int denominator = (int) Math.max(6, Math.round(lifespan * ShroomConfig.hyphaLifespan));
            if (random.nextInt(denominator) == 0) {
                die(world, pos, state);
                return;
            }
        }

        // ---- 4. Сила повільно тягнеться до цільової; стійкість і сплячка уповільнюють згасання ----
        if (target < 0 && vigor == 0) {
            if (random.nextInt(resilience + 1) == 0) {
                die(world, pos, state);
            }
            return;
        }
        int newVigor = vigor;
        if (target > vigor) {
            newVigor = vigor + 1;
        } else if (target < vigor && random.nextInt(resilience + 1) == 0 && (!dormant || random.nextInt(4) == 0)) {
            newVigor = vigor - 1;
        }
        if (newVigor != state.get(VIGOR)) {
            state = state.with(VIGOR, newVigor);
            world.setBlockState(pos, state, Block.NOTIFY_LISTENERS);
        }

        // ---- 5. Мозок ----
        if (this.part == Part.CORE) {
            coreBrain(world, pos, data, key, colony);
        }
        if (!hostOk || dormant) {
            return;
        }

        String strategy = ColonyData.strategy(colony, hasCore);

        // ---- 6. Дослідження й захоплення блоків (переважно гіфи) ----
        if (newVigor >= 2) {
            double chance = (wet ? 2 : 4) - expansion / 2.0 + resilience / 2.0;
            if (this.part != Part.HYPHA) {
                chance *= 3;
            }
            if (strategy.equals("defend")) {
                chance *= 2;
            } else if (strategy.equals("expand")) {
                chance -= 1;
            }
            int denominator = (int) Math.max(1, Math.round(chance / ShroomConfig.spreadSpeed));
            if (random.nextInt(denominator) == 0) {
                explore(world, pos, newVigor, random, data, key, colony, aggression, treeDiet);
            }
        }

        // ---- 7. Плодоношення: вузли й серце дають гриби на краю колонії ----
        if (this.part != Part.HYPHA) {
            int maxFruitVigor = fruiting >= 2 ? 6 : 5;
            int fruitChance = Math.max(2, (wet ? 4 : 8) - fruiting);
            if (newVigor >= 3 && newVigor <= maxFruitVigor && colony.points > 0 && random.nextInt(fruitChance) == 0) {
                if (fruit(world, pos)) {
                    data.spendShared(species, pos, 1);
                }
            }
        }

        // ---- 8. Закріплення: гіфа, що знайшла ресурс, стає вузлом (або вузол, що став далеким опорним) ----
        if (this.part == Part.HYPHA) {
            int rich = resourceScore(world, pos, treeDiet);
            boolean anchor = rich >= 3 && newVigor >= 2 && !hasNodeNearby(world, pos, 3);
            boolean relay = newVigor >= 4 && !hasNodeNearby(world, pos, 5);
            if (((anchor && random.nextInt(4) == 0) || (relay && random.nextInt(10) == 0))
                && data.spendShared(species, pos, 3)) {
                changePart(world, pos, state, Part.NODE);
            }
        } else if (this.part == Part.NODE && !hasCore && newVigor >= 5 && colony.points >= 5
            && random.nextInt(4) == 0 && data.spendShared(species, pos, 5)) {
            changePart(world, pos, state, Part.CORE);
            colony.hasCore = true;
            data.markDirty();
        }

        // ---- 9. Симбіоз: вузол у корінні дерев підгодовує саджанці над собою ----
        if (this.part == Part.NODE && treeDiet && !parasite && newVigor >= 4 && random.nextInt(4) == 0) {
            BlockPos above = pos.up();
            BlockState aboveState = world.getBlockState(above);
            if (aboveState.getBlock() instanceof SaplingBlock sapling) {
                sapling.generate(world, above, aboveState, random);
            }
        }
    }

    /** Сплячка: у крижаних і надто спекотних біомах колонія завмирає. */
    private static boolean isDormant(ServerWorld world, BlockPos pos) {
        float temperature = world.getBiome(pos).value().getTemperature();
        return temperature < 0.15f || temperature > 1.8f;
    }

    private void coreBrain(ServerWorld world, BlockPos pos, ColonyData data, String key, ColonyData.Colony colony) {
        if (!colony.hasCore) {
            colony.hasCore = true;
            data.markDirty();
        }
        if (colony.threat > 0) {
            colony.threat--;
        }
        if (colony.contact > 0) {
            colony.contact--;
        }
        // Серце виправляє лічильник розміру за справжнім (кількість зв'язаних блоків).
        ColonyScanner.Result real = ColonyScanner.scan(world, pos, 1500, false);
        if (real != null) {
            colony.size = real.count;
            data.markDirty();
        }
        int bought = data.tryUpgrade(key, ColonyData.dynamicPriority(species(), colony));
        if (bought >= 0) {
            announce(world, pos, bought, colony.levels[bought]);
        }
    }

    private boolean isFed(ServerWorld world, BlockPos pos) {
        BlockState above = world.getBlockState(pos.up());
        if (above.isOf(Blocks.MUSHROOM_STEM) || above.isOf(Blocks.RED_MUSHROOM_BLOCK) || above.isOf(Blocks.BROWN_MUSHROOM_BLOCK)) {
            return true;
        }
        if (!above.isOf(Registries.BLOCK.get(this.mushroomId))) {
            return false;
        }
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
            || state.isOf(Blocks.MOSS_CARPET)
            || state.isOf(Blocks.SMALL_DRIPLEAF)
            || state.isOf(Blocks.BROWN_MUSHROOM)
            || state.isOf(Blocks.RED_MUSHROOM);
    }

    private static boolean isMushroom(BlockState state) {
        return state.getBlock() instanceof ShroomBlock
            || state.isOf(Blocks.BROWN_MUSHROOM)
            || state.isOf(Blocks.RED_MUSHROOM);
    }

    private static boolean hasHostTree(WorldView world, BlockPos pos) {
        for (BlockPos nearby : BlockPos.iterate(pos.add(-3, -1, -3), pos.add(3, 5, 3))) {
            if (world.getBlockState(nearby).isIn(BlockTags.LOGS)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasNodeNearby(ServerWorld world, BlockPos pos, int radius) {
        for (BlockPos nearby : BlockPos.iterate(pos.add(-radius, -2, -radius), pos.add(radius, 2, radius))) {
            if (nearby.equals(pos)) {
                continue;
            }
            if (world.getBlockState(nearby).getBlock() instanceof MyceliumBlock block
                && block.species().equals(species()) && block.part != Part.HYPHA) {
                return true;
            }
        }
        return false;
    }

    /** Скільки ресурсів поряд: рослини (або дерева для кореневих видів) і вода. Чим більше, тим «ситніше» місце. */
    private int resourceScore(ServerWorld world, BlockPos center, boolean treeDiet) {
        int score = 0;
        for (BlockPos nearby : BlockPos.iterate(center.add(-3, -1, -3), center.add(3, 2, 3))) {
            BlockState state = world.getBlockState(nearby);
            if (treeDiet ? state.isIn(BlockTags.LOGS) : isPrey(state)) {
                score++;
            } else if (world.getFluidState(nearby).isIn(FluidTags.WATER)) {
                score++;
            }
        }
        return Math.min(8, score);
    }

    private int ownNeighbours(ServerWorld world, BlockPos pos) {
        int count = 0;
        for (BlockPos nearby : BlockPos.iterate(pos.add(-1, -1, -1), pos.add(1, 1, 1))) {
            if (world.getBlockState(nearby).getBlock() instanceof MyceliumBlock block
                && block.species().equals(species())) {
                count++;
            }
        }
        return count;
    }

    // =====================================================================
    //  Дослідження, захоплення, війни
    // =====================================================================

    /** Скільки очок коштує освоїти хазяїна цього виду; 0 = вид не вміє. */
    private static int hostCost(String species, Host host) {
        if (host == Host.HAY) {
            return species.equals("penicillium") ? 1 : 0;
        }
        Host.Kind kind = host.kind();
        return switch (species) {
            case "mycena_chlorophos" -> switch (kind) {
                case WOOD -> 1;
                case SOIL -> 2;
                case ROCK -> 3;
                default -> 0;
            };
            case "honey_fungus" -> switch (kind) {
                case WOOD -> 1;
                case SOIL -> 2;
                case LOOSE -> 3;
                default -> 0;
            };
            case "gibellula" -> switch (kind) {
                case ROCK, DEEP -> 1;
                case SOIL -> 2;
                case WOOD -> 3;
                default -> 0;
            };
            case "penicillium" -> switch (kind) {
                case WOOD, SOIL -> 1;
                case ROCK, LOOSE -> 2;
                default -> 0;
            };
            case "morel" -> switch (kind) {
                case SOIL -> 1;
                case LOOSE, WOOD -> 2;
                case ROCK -> 3;
                default -> 0;
            };
            case "death_cap" -> switch (kind) {
                case SOIL, WOOD -> 1;
                case LOOSE -> 3;
                default -> 0;
            };
            case "inky_cap", "brown_mushroom" -> switch (kind) {
                case SOIL, WOOD -> 1;
                case LOOSE -> 2;
                case ROCK -> 3;
                default -> 0;
            };
            default -> switch (kind) {
                case SOIL -> 1;
                case WOOD -> 2;
                case LOOSE -> 3;
                default -> 0;
            };
        };
    }

    /** Чи може ця частина грибниці взагалі захопити такого хазяїна (з урахуванням конфігу). */
    private boolean hostAllowed(Host host) {
        Host.Kind kind = host.kind();
        // Серце й вузли захоплюють лише м'який ґрунт і пісок. Камінь, гравій, деревину, сіно беруть гіфи.
        if (this.part != Part.HYPHA && kind != Host.Kind.SOIL && host != Host.SAND) {
            return false;
        }
        return switch (kind) {
            case ROCK, DEEP -> ShroomConfig.captureStone;
            case WOOD -> ShroomConfig.captureWood;
            case LOOSE -> ShroomConfig.captureSand;
            case ORGANIC -> ShroomConfig.moldEatsHay;
            case SOIL -> true;
        };
    }

    /** Наскільки приваблива ціль: дешевий хазяїн, ресурси поруч, їжа над блоком, слабкий ворог. */
    private int scoreTarget(ServerWorld world, BlockPos target, int aggression, boolean treeDiet) {
        BlockState state = world.getBlockState(target);
        if (state.getBlock() instanceof MyceliumBlock other) {
            if (other.species().equals(species()) || !ShroomConfig.warEnabled) {
                return -100;
            }
            return 2 + aggression + (isMold() ? 1 : 0);
        }
        if (state.isOf(Blocks.MYCELIUM)) {
            return 1;
        }
        Host host = Host.of(state);
        if (host == null || !hostAllowed(host)) {
            return -100;
        }
        int cost = hostCost(species(), host);
        if (cost <= 0) {
            return -100;
        }
        int score = 6 - cost * 2;
        score += Math.min(5, resourceScore(world, target, treeDiet));
        if (isPrey(world.getBlockState(target.up()))) {
            score += 2;
        }
        if (this.diet == Diet.PARASITE && host.kind() == Host.Kind.WOOD) {
            score += 6;
        }
        // Гіфи тяжіють до краю колонії, а не до центру.
        score -= ownNeighbours(world, target) / 4;
        return score;
    }

    /** Напрямлений ріст: з кількох кандидатів гіфа обирає найкращого, тобто росте до ресурсу. */
    private void explore(ServerWorld world, BlockPos pos, int vigor, Random random, ColonyData data, String key,
                         ColonyData.Colony colony, int aggression, boolean treeDiet) {
        BlockPos best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < 5; i++) {
            int dx = random.nextInt(3) - 1;
            int dy = random.nextInt(3) - 1;
            int dz = random.nextInt(3) - 1;
            if (dx == 0 && dy == 0 && dz == 0) {
                continue;
            }
            BlockPos candidate = pos.add(dx, dy, dz);
            int score = scoreTarget(world, candidate, aggression, treeDiet);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        if (best == null || bestScore < -50) {
            return;
        }
        attempt(world, pos, vigor, best, random, data, key, colony, aggression);
    }

    private void attempt(ServerWorld world, BlockPos pos, int vigor, BlockPos target, Random random,
                         ColonyData data, String key, ColonyData.Colony colony, int aggression) {
        BlockState targetState = world.getBlockState(target);

        if (targetState.getBlock() instanceof MyceliumBlock other) {
            if (!other.species().equals(species()) && ShroomConfig.warEnabled) {
                fight(world, pos, vigor, target, targetState, other, random, data, key, colony, aggression);
            }
            return;
        }
        if (targetState.isOf(Blocks.MYCELIUM)) {
            if (vigor + 2 * aggression + random.nextInt(3) > 4 + random.nextInt(3)) {
                place(world, target, Host.DIRT, Part.HYPHA, vigor, data, colony);
            }
            return;
        }

        Host host = Host.of(targetState);
        if (host == null || !hostAllowed(host)) {
            return;
        }
        int cost = hostCost(species(), host);
        if (cost <= 0 || colony.size >= ShroomConfig.maxColonyBlocks) {
            return;
        }
        if (this.diet != Diet.PLANTS && !hasHostTree(world, target)) {
            return;
        }
        if (!data.spendShared(species(), pos, cost)) {
            return;
        }
        place(world, target, host, Part.HYPHA, vigor, data, colony);

        // Добування ресурсів: тліюча деревина годує, паразит убиває дерево й отримує багато.
        if (host.kind() == Host.Kind.WOOD) {
            int gain = (this.diet == Diet.PARASITE && ShroomConfig.parasiteEnabled) ? 6 : (this.diet == Diet.PLANTS ? 2 : 0);
            if (gain > 0) {
                data.addPoints(key, gain);
            }
        }
    }

    private void place(ServerWorld world, BlockPos target, Host host, Part newPart, int vigor,
                       ColonyData data, ColonyData.Colony colony) {
        MyceliumBlock block = of(species(), newPart);
        if (block == null) {
            return;
        }
        world.setBlockState(target, block.getDefaultState().with(HOST, host).with(VIGOR, Math.max(1, vigor - 2)),
            Block.NOTIFY_LISTENERS);
        colony.size++;
        data.markDirty();
    }

    private void fight(ServerWorld world, BlockPos pos, int vigor, BlockPos target, BlockState targetState,
                       MyceliumBlock other, Random random, ColonyData data, String key,
                       ColonyData.Colony attackerColony, int aggression) {
        // Напад коштує очко: без запасів колонія не воює.
        if (!data.spendShared(species(), pos, 1)) {
            return;
        }
        String otherKey = ColonyData.key(other.species(), target);
        ColonyData.Colony defender = data.colony(otherKey);
        defender.threat = 6;
        attackerColony.contact = 6;

        int partBonus = switch (other.part) {
            case CORE -> 3;
            case NODE -> 1;
            case HYPHA -> 0;
        };
        int[] defenderLevels = data.effectiveLevels(other.species(), target);
        int reinforce = (defender.points > 0 && data.spend(otherKey, 1)) ? 2 : 0;
        int attack = vigor + 2 * aggression + (isMold() ? 1 : 0) + random.nextInt(3);
        int defence = targetState.get(VIGOR) + 2 * defenderLevels[ColonyData.RESILIENCE] + partBonus + reinforce + random.nextInt(3);

        if (attack > defence) {
            Part newPart = other.part == Part.CORE ? Part.NODE : other.part;
            Host host = targetState.get(HOST);
            place(world, target, host, newPart, vigor, data, attackerColony);
            defender.size = Math.max(0, defender.size - 1);
            if (other.part == Part.CORE) {
                defender.hasCore = false;
            }
            data.addPoints(key, 1);
            BlockPos above = target.up();
            BlockState aboveState = world.getBlockState(above);
            if (isMushroom(aboveState) && !aboveState.isOf(Registries.BLOCK.get(this.mushroomId))) {
                world.breakBlock(above, false);
            }
            world.spawnParticles(ParticleTypes.SPORE_BLOSSOM_AIR,
                target.getX() + 0.5, target.getY() + 1.0, target.getZ() + 0.5, 8, 0.3, 0.2, 0.3, 0.01);
        } else {
            BlockState self = world.getBlockState(pos);
            if (self.isOf(this) && self.get(VIGOR) > 0) {
                world.setBlockState(pos, self.with(VIGOR, self.get(VIGOR) - 1), Block.NOTIFY_LISTENERS);
            }
            data.addPoints(otherKey, 1);
        }
    }

    // =====================================================================
    //  Перетворення, смерть, плодоношення
    // =====================================================================

    private void changePart(ServerWorld world, BlockPos pos, BlockState state, Part newPart) {
        MyceliumBlock block = of(species(), newPart);
        if (block != null) {
            world.setBlockState(pos, block.getDefaultState().with(HOST, state.get(HOST)).with(VIGOR, state.get(VIGOR)),
                Block.NOTIFY_LISTENERS);
        }
    }

    /** Грибниця гине: блок хазяїна повертається, колонія втрачає частину. */
    public void die(ServerWorld world, BlockPos pos, BlockState state) {
        ColonyData data = ColonyData.get(world);
        ColonyData.Colony colony = data.colony(ColonyData.key(species(), pos));
        colony.size = Math.max(0, colony.size - 1);
        if (this.part == Part.CORE) {
            colony.hasCore = false;
        }
        data.markDirty();
        world.setBlockState(pos, state.get(HOST).original(), Block.NOTIFY_ALL);
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

    private void announce(ServerWorld world, BlockPos pos, int upgrade, int level) {
        Text message = Text.translatable("message.realshrooms.upgrade", this.getName(),
            Text.translatable("upgrade.realshrooms." + ColonyData.UPGRADE_NAMES[upgrade]), level);
        for (ServerPlayerEntity player : world.getPlayers(p -> p.squaredDistanceTo(pos.getX(), pos.getY(), pos.getZ()) < 1024.0)) {
            player.sendMessage(message, true);
        }
    }

    // =====================================================================
    //  Взаємодія гравця
    // =====================================================================

    /** ПКМ порожньою рукою: оглянути колонію (розмір реальний, порахований по зв'язаних блоках). */
    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (hand != Hand.MAIN_HAND || !player.getStackInHand(hand).isEmpty()) {
            return ActionResult.PASS;
        }
        if (world instanceof ServerWorld serverWorld) {
            ColonyData data = ColonyData.get(serverWorld);
            ColonyData.Colony colony = data.colony(ColonyData.key(species(), pos));
            int[] levels = data.effectiveLevels(species(), pos);
            ColonyScanner.Result real = ColonyScanner.scan(serverWorld, pos, 1500, false);
            int size = real != null ? real.count : colony.size;
            player.sendMessage(Text.translatable("message.realshrooms.colony", this.getName(),
                Text.translatable("strategy.realshrooms." + ColonyData.strategy(colony, data.effectiveHasCore(species(), pos))),
                colony.points, size,
                levels[0], levels[1], levels[2], levels[3], state.get(VIGOR)), false);
        }
        return ActionResult.success(world.isClient);
    }

    // ---------- кісткове борошно: підживлює колонію (віддача спадає) ----------

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
        ColonyData.Colony colony = data.colony(key);
        // Спадна віддача: багата колонія майже не отримує очок від кісткового борошна.
        int gain = colony.points < 20 ? 5 : (colony.points < 50 ? 2 : 0);
        if (gain > 0) {
            data.addPoints(key, gain);
        }
        int[] levels = data.effectiveLevels(species(), pos);
        explore(world, pos, vigor, random, data, key, colony, levels[ColonyData.AGGRESSION], this.diet != Diet.PLANTS);
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
