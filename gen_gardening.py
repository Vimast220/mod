"""Запуск з кореня проєкту: python3 tools/gen_gardening.py
Спершу виконує gen_assets.py (текстури, базові ресурси), потім додає садівництво:
стадії росту, субстрат, лут, рецепти, теги й підказки."""
import json, os, random, runpy
from PIL import Image

runpy.run_path("tools/gen_assets.py")

MOD = "realshrooms"
R = "src/main/resources"

def w(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        if isinstance(data, str): f.write(data)
        else: json.dump(data, f, ensure_ascii=False, indent=2)

SPECIES = ["chanterelle", "porcini", "morel", "fly_agaric", "death_cap", "inky_cap", "mycena_chlorophos", "honey_fungus"]
VANILLA = ["brown_mushroom", "red_mushroom"]   # колонії ванільних грибів (плодові тіла: minecraft:*)
MYC_SPECIES = SPECIES + VANILLA
PARTS = ["core", "node", "hypha"]
HOSTS = ["dirt", "stone", "deepslate", "sand", "gravel", "wood"]
PART_BLOCKS = [f"{MOD}:{x}_{p}" for x in MYC_SPECIES for p in PARTS]

# ---------- стадії росту: зменшені «хрестики» ----------
SCALES = [0.45, 0.75, 1.0]
def cross(tex, s):
    lo, hi, h = 8 - 7.2 * s, 8 + 7.2 * s, 16 * s
    face = {"uv": [0, 0, 16, 16], "texture": "#cross"}
    rot = {"origin": [8, 8, 8], "axis": "y", "angle": 45, "rescale": True}
    return {"ambientocclusion": False,
            "textures": {"particle": "#cross", "cross": tex},
            "elements": [
              {"from": [lo, 0, 8], "to": [hi, h, 8], "rotation": rot, "shade": False,
               "faces": {"north": face, "south": face}},
              {"from": [8, 0, lo], "to": [8, h, hi], "rotation": rot, "shade": False,
               "faces": {"west": face, "east": face}}]}

for sid in SPECIES:
    for age, s in enumerate(SCALES):
        w(f"{R}/assets/{MOD}/models/block/{sid}_age{age}.json", cross(f"{MOD}:block/{sid}", s))
    w(f"{R}/assets/{MOD}/blockstates/{sid}.json", {"variants": {
        f"age={a}": {"model": f"{MOD}:block/{sid}_age{a}"} for a in range(3)}})
    ref = f"{MOD}:{sid}"
    # врожай: 1 гриб завжди (щоб можна було пересадити) + 0..2 додатково зі стиглого
    w(f"{R}/data/{MOD}/loot_tables/blocks/{sid}.json", {"type": "minecraft:block", "pools": [
        {"rolls": 1, "entries": [{"type": "minecraft:item", "name": ref}],
         "conditions": [{"condition": "minecraft:survives_explosion"}]},
        {"rolls": 1, "entries": [{"type": "minecraft:item", "name": ref, "functions": [
            {"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": 0, "max": 2}}]}],
         "conditions": [{"condition": "minecraft:survives_explosion"},
                        {"condition": "minecraft:block_state_property", "block": ref, "properties": {"age": "2"}}]}]})

# ---------- субстрат ----------
def substrate_tex(name, base, seed):
    rnd = random.Random(seed)
    img = Image.new("RGBA", (16, 16))
    for x in range(16):
        for y in range(16):
            d = rnd.randint(-14, 14)
            c = tuple(max(0, min(255, v + d)) for v in base)
            if rnd.random() < 0.10:  # соломинки
                c = (min(255, base[0] + 70), min(255, base[1] + 55), base[2] + 10)
            elif rnd.random() < 0.06:
                c = (base[0] // 2, base[1] // 2, base[2] // 2)
            img.putpixel((x, y), c + (255,))
    os.makedirs(f"{R}/assets/{MOD}/textures/block", exist_ok=True)
    img.save(f"{R}/assets/{MOD}/textures/block/{name}.png")
substrate_tex("mushroom_substrate", (112, 84, 52), 1)
substrate_tex("mushroom_substrate_moist", (72, 52, 34), 2)
for n in ("mushroom_substrate", "mushroom_substrate_moist"):
    w(f"{R}/assets/{MOD}/models/block/{n}.json", {"parent": "minecraft:block/cube_all", "textures": {"all": f"{MOD}:block/{n}"}})
w(f"{R}/assets/{MOD}/models/item/mushroom_substrate.json", {"parent": f"{MOD}:block/mushroom_substrate"})
w(f"{R}/assets/{MOD}/blockstates/mushroom_substrate.json", {"variants": {
    "moist=false": {"model": f"{MOD}:block/mushroom_substrate"},
    "moist=true": {"model": f"{MOD}:block/mushroom_substrate_moist"}}})
w(f"{R}/data/{MOD}/loot_tables/blocks/mushroom_substrate.json", {"type": "minecraft:block", "pools": [
    {"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{MOD}:mushroom_substrate"}],
     "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
w(f"{R}/data/minecraft/tags/blocks/dirt.json", {"replace": False, "values": [f"{MOD}:mushroom_substrate"]})
for tool in ("shovel", "pickaxe", "axe"):
    vals = PART_BLOCKS + ([f"{MOD}:mushroom_substrate"] if tool == "shovel" else [])
    w(f"{R}/data/minecraft/tags/blocks/mineable/{tool}.json", {"replace": False, "values": vals})

# ---------- шашлик + текстура ----------
def hexc(h): h = h.lstrip('#'); return tuple(int(h[i:i+2], 16) for i in (0, 2, 4)) + (255,)
sk = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
for i in range(14):  # паличка по діагоналі
    sk.putpixel((1 + i, 14 - i), hexc("9C7A45"))
for (cx, cy, cap, stem) in ((4, 11, "8B5A2B", "EBDDBF"), (8, 7, "E39A1B", "F0D9A0"), (11, 4, "8B5A2B", "EBDDBF")):
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            sk.putpixel((cx + dx, cy + dy), hexc(cap if dx + dy <= 0 else stem))
sk.save(f"{R}/assets/{MOD}/textures/item/mushroom_skewer.png")
w(f"{R}/assets/{MOD}/models/item/mushroom_skewer.json", {"parent": "minecraft:item/generated", "textures": {"layer0": f"{MOD}:item/mushroom_skewer"}})

# ---------- рецепти ----------
rc = f"{R}/data/{MOD}/recipes"
w(f"{rc}/mushroom_substrate.json", {"type": "minecraft:crafting_shapeless", "category": "building",
    "ingredients": [{"item": "minecraft:dirt"}, {"item": "minecraft:dirt"}, {"item": "minecraft:wheat"}, {"item": "minecraft:bone_meal"}],
    "result": {"item": f"{MOD}:mushroom_substrate", "count": 2}})
edible = [{"item": f"{MOD}:chanterelle"}, {"item": f"{MOD}:porcini"}, {"item": f"{MOD}:cooked_morel"}]
w(f"{rc}/mushroom_skewer.json", {"type": "minecraft:crafting_shapeless", "category": "food",
    "ingredients": [{"item": "minecraft:stick"}, edible, edible],
    "result": {"item": f"{MOD}:mushroom_skewer"}})

w(f"{rc}/glow_ink_sac_from_chlorophos.json", {"type": "minecraft:crafting_shapeless", "category": "misc",
    "ingredients": [{"item": "minecraft:ink_sac"}, {"item": f"{MOD}:mycena_chlorophos"}, {"item": f"{MOD}:mycena_chlorophos"}],
    "result": {"item": "minecraft:glow_ink_sac"}})

# ---------- переклади + підказки ----------
def merge_lang(code, add):
    p = f"{R}/assets/{MOD}/lang/{code}.json"
    d = json.load(open(p, encoding="utf-8")); d.update(add)
    w(p, d)

en = {f"block.{MOD}.mushroom_substrate": "Mushroom Substrate", f"item.{MOD}.mushroom_skewer": "Mushroom Skewer"}
uk = {f"block.{MOD}.mushroom_substrate": "Грибний субстрат", f"item.{MOD}.mushroom_skewer": "Шашлик із грибів"}

GEN_EN = "Right-click when ripe to harvest. Shears: +1 and faster regrowth."
GEN_UK = "ПКМ по стиглому грибу: зібрати врожай. З ножицями +1 і швидше відростає."
TREE_EN = "Needs trees nearby, or plant it on Mushroom Substrate."
TREE_UK = "Потрібні дерева поруч, або саджай на грибний субстрат."
NOTE = {  # (en, uk)
 "chanterelle": ("Edible and tasty.", "Їстівна й смачна."),
 "porcini": ("Edible and very filling.", "Їстівний і дуже ситний."),
 "morel": ("Toxic raw! Cook it first.", "Сирий токсичний! Спершу засмаж."),
 "fly_agaric": ("Poisonous. Do not eat.", "Отруйний. Не їсти."),
 "death_cap": ("Deadly poisonous!", "Смертельно отруйна!"),
 "inky_cap": ("Two of them make an ink sac.", "З двох виходить мішечок із чорнилом."),
 "mycena_chlorophos": ("Bioluminescent. Too small to eat, edibility unknown.", "Біолюмінесцентний. Замалий, щоб їсти; їстівність невідома."),
 "honey_fungus": ("Raw it is toxic. A parasite: its mycelium kills trees.", "Сирий токсичний. Паразит: його грибниця вбиває дерева."),
}
for sid in SPECIES:
    k = f"tooltip.{MOD}.{sid}"
    en[f"{k}.1"], uk[f"{k}.1"] = GEN_EN, GEN_UK
    en[f"{k}.2"], uk[f"{k}.2"] = NOTE[sid]
    if sid == "mycena_chlorophos":
        en[f"{k}.3"], uk[f"{k}.3"] = "Tropical: needs humidity (rain or water nearby). Grows on dead wood; wilts when dry.", "Тропічний: потрібна волога (дощ або вода поруч). Росте на мертвій деревині; в'яне в посуху."
    elif sid in ("inky_cap", "morel"):
        en[f"{k}.3"], uk[f"{k}.3"] = "Needs no trees. Its mycelium devours plants nearby.", "Дерева не потрібні. Грибниця поглинає рослини навколо."
    else:
        en[f"{k}.3"], uk[f"{k}.3"] = TREE_EN, TREE_UK
k = f"tooltip.{MOD}.mushroom_substrate"
en[f"{k}.1"], uk[f"{k}.1"] = "Keep it moist: water within 4 blocks, or rain.", "Тримай вологим: вода в радіусі 4 блоків або дощ."
en[f"{k}.2"], uk[f"{k}.2"] = "Mushrooms on moist substrate grow fast.", "Гриби на вологому субстраті ростуть швидко."
en[f"{k}.3"], uk[f"{k}.3"] = "Any species grows here, even without trees.", "Тут росте будь-який вид, навіть без дерев."
merge_lang("en_us", en); merge_lang("uk_ua", uk)


# ================= ГРИБНИЦЯ: частини (головна, вузол, гіфи) і хазяїни =================
TINT = {"chanterelle": "F5C04A", "porcini": "A9703A", "morel": "B08B4F", "fly_agaric": "E03A33",
        "death_cap": "A7AC50", "inky_cap": "9A9A9A", "mycena_chlorophos": "46C8FF", "honey_fungus": "D6AA46",
        "brown_mushroom": "A9703A", "red_mushroom": "E03A33"}
THREAD = {"mycena_chlorophos": (150, 225, 250), "honey_fungus": (70, 50, 35)}   # решта: білі нитки

def tier_of(v): return 0 if v <= 2 else (1 if v <= 5 else 2)

def overlay_tex(part, sid, tier):
    """Напівпрозорі нитки, які лягають поверх захопленого блока."""
    rnd = random.Random(sum(map(ord, sid)) * 31 + PARTS.index(part) * 7 + tier)
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    thread = THREAD.get(sid, (232, 232, 220))
    tint = hexc(TINT[sid])
    strands = {"hypha": [2, 5, 9], "node": [5, 9, 14], "core": [8, 13, 20]}[part][tier]
    for _ in range(strands):
        x, y = rnd.randint(0, 15), rnd.randint(0, 15)
        for _ in range(rnd.randint(4, 9)):
            img.putpixel((x % 16, y % 16), thread + (235,))
            x += rnd.choice((-1, 0, 1)); y += rnd.choice((-1, 0, 1))
    for _ in range([0, 2, 4][tier] + (2 if part != "hypha" else 0)):
        img.putpixel((rnd.randint(0, 15), rnd.randint(0, 15)), tint)
    if part == "node":      # вузол: пара «вузликів»
        for _ in range(2):
            cx, cy = rnd.randint(2, 13), rnd.randint(2, 13)
            for dx in (0, 1):
                for dy in (0, 1):
                    img.putpixel((cx + dx, cy + dy), thread + (255,))
            img.putpixel((cx, cy), tint)
    if part == "core":      # головна: кільце навколо яскравого центру
        for a in range(16):
            for b in range(16):
                d = ((a - 7.5) ** 2 + (b - 7.5) ** 2) ** 0.5
                if 4.2 < d < 5.0 and rnd.random() < 0.85:
                    img.putpixel((a, b), thread + (255,))
                if d < 2.0:
                    img.putpixel((a, b), tint)
    return img

tex_dir = f"{R}/assets/{MOD}/textures/block"
os.makedirs(tex_dir, exist_ok=True)
for sid in MYC_SPECIES:
    for part in PARTS:
        for tier in range(3):
            overlay_tex(part, sid, tier).save(f"{tex_dir}/mycelium_overlay_{part}_{sid}_{tier}.png")

# Блок-хазяїн (ванільні текстури) + шар ниток зверху.
HOST_TEX = {"dirt": ("minecraft:block/dirt", "minecraft:block/dirt"),
            "stone": ("minecraft:block/stone", "minecraft:block/stone"),
            "deepslate": ("minecraft:block/deepslate", "minecraft:block/deepslate_top"),
            "sand": ("minecraft:block/sand", "minecraft:block/sand"),
            "gravel": ("minecraft:block/gravel", "minecraft:block/gravel"),
            "wood": ("minecraft:block/oak_log", "minecraft:block/oak_log_top")}
DIRS = ("down", "up", "north", "south", "west", "east")
def host_model(host, overlay):
    side, end = HOST_TEX[host]
    base = {d: {"texture": "#end" if d in ("down", "up") else "#side", "cullface": d} for d in DIRS}
    over = {d: {"texture": "#over"} for d in DIRS}
    return {"textures": {"particle": side, "side": side, "end": end, "over": overlay},
            "elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": base},
                         {"from": [-0.01, -0.01, -0.01], "to": [16.01, 16.01, 16.01], "faces": over}]}

SILK = {"condition": "minecraft:match_tool", "predicate": {"enchantments": [
    {"enchantment": "minecraft:silk_touch", "levels": {"min": 1}}]}}
NO_SILK = {"condition": "minecraft:inverted", "term": SILK}
DROPS = {"dirt": "minecraft:dirt", "stone": "minecraft:cobblestone", "deepslate": "minecraft:cobbled_deepslate",
         "sand": "minecraft:sand", "gravel": "minecraft:gravel"}

for sid in MYC_SPECIES:
    for part in PARTS:
        b = f"{sid}_{part}"
        for host in HOSTS:
            for tier in range(3):
                w(f"{R}/assets/{MOD}/models/block/mycelium_{part}_{sid}_{host}_{tier}.json",
                  host_model(host, f"{MOD}:block/mycelium_overlay_{part}_{sid}_{tier}"))
        w(f"{R}/assets/{MOD}/blockstates/{b}.json", {"variants": {
            f"host={h},vigor={v}": {"model": f"{MOD}:block/mycelium_{part}_{sid}_{h}_{tier_of(v)}"}
            for h in HOSTS for v in range(8)}})
        # Лут: Silk Touch забирає головну грибницю цілою (колонію можна перенести), решта дає блок хазяїна.
        pools = []
        if part == "core":
            pools.append({"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{MOD}:{b}"}], "conditions": [SILK]})
        for host, item in DROPS.items():
            conds = ([NO_SILK] if part == "core" else []) + [
                {"condition": "minecraft:block_state_property", "block": f"{MOD}:{b}", "properties": {"host": host}}]
            pools.append({"rolls": 1, "entries": [{"type": "minecraft:item", "name": item}], "conditions": conds})
        pools.append({"rolls": 1, "entries": [{"type": "minecraft:item", "name": "minecraft:stick", "functions": [
            {"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": 0, "max": 2}}]}],
            "conditions": [{"condition": "minecraft:block_state_property", "block": f"{MOD}:{b}", "properties": {"host": "wood"}}]})
        w(f"{R}/data/{MOD}/loot_tables/blocks/{b}.json", {"type": "minecraft:block", "pools": pools})
    # предмет і рецепт є лише в головної грибниці
    w(f"{R}/assets/{MOD}/models/item/{sid}_core.json", {"parent": f"{MOD}:block/mycelium_core_{sid}_dirt_2"})
    w(f"{R}/data/{MOD}/recipes/{sid}_core.json", {"type": "minecraft:crafting_shapeless", "category": "misc",
        "ingredients": [{"item": (f"{MOD}:{sid}" if sid in SPECIES else f"minecraft:{sid}")}, {"item": f"{MOD}:mushroom_substrate"}],
        "result": {"item": f"{MOD}:{sid}_core"}})

# ванільні гриби виживають на будь-якій нашій грибниці незалежно від світла
w(f"{R}/data/minecraft/tags/blocks/mushroom_grow_block.json", {"replace": False, "values": PART_BLOCKS})

# ---------- переклади ----------
SHORT_EN = {"chanterelle": "Chanterelle", "porcini": "Porcini", "morel": "Morel", "fly_agaric": "Fly Agaric",
            "death_cap": "Death Cap", "inky_cap": "Inky Cap", "mycena_chlorophos": "Mycena Chlorophos",
            "honey_fungus": "Honey Fungus", "brown_mushroom": "Brown Mushroom", "red_mushroom": "Red Mushroom"}
SHORT_UK = {"chanterelle": "лисичка", "porcini": "білий гриб", "morel": "зморшок", "fly_agaric": "мухомор",
            "death_cap": "бліда поганка", "inky_cap": "гнойовик", "mycena_chlorophos": "мицена хлорофос",
            "honey_fungus": "опеньок", "brown_mushroom": "коричневий гриб", "red_mushroom": "червоний гриб"}
PLANT_DIET = {"inky_cap", "morel", "mycena_chlorophos", "brown_mushroom"}
en2, uk2 = {}, {}
for sid in MYC_SPECIES:
    e, u = SHORT_EN[sid], SHORT_UK[sid]
    en2[f"block.{MOD}.{sid}_core"] = f"{e} Mycelium Core"
    en2[f"block.{MOD}.{sid}_node"] = f"{e} Mycelium Node"
    en2[f"block.{MOD}.{sid}_hypha"] = f"{e} Hyphae"
    uk2[f"block.{MOD}.{sid}_core"] = f"Головна грибниця: {u}"
    uk2[f"block.{MOD}.{sid}_node"] = f"Вузол грибниці: {u}"
    uk2[f"block.{MOD}.{sid}_hypha"] = f"Гіфи: {u}"
    k = f"tooltip.{MOD}.{sid}_core"
    en2[f"{k}.1"], uk2[f"{k}.1"] = "The brain of a colony. Place it to found one.", "Мозок колонії. Постав, щоб заснувати її."
    if sid == "honey_fungus":
        en2[f"{k}.2"], uk2[f"{k}.2"] = "Parasite: rots logs and kills trees for energy.", "Паразит: зотліває колоди й вбиває дерева заради енергії."
    elif sid in PLANT_DIET:
        en2[f"{k}.2"], uk2[f"{k}.2"] = "Devours plants around it: grass, flowers, saplings.", "Поглинає рослини навколо: траву, квіти, саджанці."
    else:
        en2[f"{k}.2"], uk2[f"{k}.2"] = "Lives in tree roots, feeds on water and soil. Dies without trees.", "Живе в корінні дерев, живиться водою й ґрунтом. Без дерев гине."
    en2[f"{k}.3"], uk2[f"{k}.3"] = "Nodes feed fruiting bodies. Hyphae explore, harvest and are unstable.", "Вузли дають гриби. Гіфи досліджують, збирають ресурси й нестабільні."
    en2[f"{k}.4"], uk2[f"{k}.4"] = "Earns points from food, pays upkeep, grows and fights. Empty hand: inspect.", "Заробляє очки з їжі, платить утримання, росте й воює. Порожня рука: оглянути."
en2[f"message.{MOD}.colony"] = "%s | %s | points: %s | size: %s | Expansion %s, Fruiting %s, Resilience %s, Aggression %s | vigor %s"
uk2[f"message.{MOD}.colony"] = "%s | %s | очки: %s | розмір: %s | Експансія %s, Плодоношення %s, Стійкість %s, Агресія %s | сила %s"
en2[f"message.{MOD}.upgrade"] = "%s upgraded: %s (level %s)"
uk2[f"message.{MOD}.upgrade"] = "%s розвинула: %s (рівень %s)"
for up, (e_name, u_name) in {"expansion": ("Expansion", "Експансія"), "fruiting": ("Fruiting", "Плодоношення"),
                             "resilience": ("Resilience", "Стійкість"), "aggression": ("Aggression", "Агресія")}.items():
    en2[f"upgrade.{MOD}.{up}"], uk2[f"upgrade.{MOD}.{up}"] = e_name, u_name
for st, (e_name, u_name) in {"headless": ("Headless", "Без голови"), "hungry": ("Starving", "Голод"),
                             "defend": ("Defending", "Оборона"), "war": ("At war", "Війна"),
                             "expand": ("Expanding", "Розширення"), "stable": ("Stable", "Стабільна")}.items():
    en2[f"strategy.{MOD}.{st}"], uk2[f"strategy.{MOD}.{st}"] = e_name, u_name
merge_lang("en_us", en2); merge_lang("uk_ua", uk2)

# перевірка JSON
for root, _, files in os.walk(R):
    for f in files:
        if f.endswith(".json"): json.load(open(os.path.join(root, f), encoding="utf-8"))
print("gardening assets done")
