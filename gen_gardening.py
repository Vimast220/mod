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

SPECIES = ["chanterelle", "porcini", "morel", "fly_agaric", "death_cap", "inky_cap", "mycena_chlorophos"]
MYC = [f"{MOD}:{x}_mycelium" for x in SPECIES]

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
w(f"{R}/data/minecraft/tags/blocks/mineable/shovel.json", {"replace": False, "values": [f"{MOD}:mushroom_substrate"] + MYC})
w(f"{R}/data/minecraft/tags/blocks/dirt.json", {"replace": False, "values": [f"{MOD}:mushroom_substrate"] + MYC})

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


# ================= ГРИБНИЦЯ =================
TINT = {"chanterelle": "F5C04A", "porcini": "A9703A", "morel": "B08B4F",
        "fly_agaric": "E03A33", "death_cap": "A7AC50", "inky_cap": "9A9A9A", "mycena_chlorophos": "46C8FF"}

def soil(rnd, base):
    img = Image.new("RGBA", (16, 16))
    for x in range(16):
        for y in range(16):
            d = rnd.randint(-12, 12)
            img.putpixel((x, y), tuple(max(0, min(255, v + d)) for v in base) + (255,))
    return img

def hyphae(img, rnd, strands, tint, specks, white=(232, 232, 220, 255)):
    for _ in range(strands):
        x, y = rnd.randint(0, 15), rnd.randint(0, 15)
        for _ in range(rnd.randint(5, 11)):
            img.putpixel((x % 16, y % 16), white)
            x += rnd.choice((-1, 0, 1)); y += rnd.choice((-1, 0, 1))
    for _ in range(specks):
        img.putpixel((rnd.randint(0, 15), rnd.randint(0, 15)), hexc(tint))

STRANDS = [3, 9, 18]; SPECKS = [0, 3, 6]
tex_dir = f"{R}/assets/{MOD}/textures/block"
for sid in SPECIES:
    for tier in range(3):
        rnd = random.Random(sum(map(ord, sid)) * 10 + tier)
        top = soil(rnd, (104, 80, 52)); hyphae(top, rnd, STRANDS[tier], TINT[sid], SPECKS[tier], white=(150, 225, 250, 255) if sid == "mycena_chlorophos" else (232, 232, 220, 255))
        top.save(f"{tex_dir}/{sid}_mycelium_top_{tier}.png")
for tier in range(3):
    rnd = random.Random(500 + tier)
    side = soil(rnd, (112, 84, 54)); hyphae(side, rnd, [1, 3, 6][tier], "FFFFFF", 0)
    for x in range(16):  # білий «край» зверху
        if rnd.random() < [0.2, 0.5, 0.8][tier]: side.putpixel((x, 0), (232, 232, 220, 255))
    side.save(f"{tex_dir}/mycelium_side_{tier}.png")

def tier_of(v): return 0 if v <= 2 else (1 if v <= 5 else 2)

en2, uk2 = {}, {}
NAMES = {"chanterelle": ("Chanterelle Mycelium", "Грибниця лисички"),
         "porcini": ("Porcini Mycelium", "Грибниця білого гриба"),
         "morel": ("Morel Mycelium", "Грибниця зморшка"),
         "fly_agaric": ("Fly Agaric Mycelium", "Грибниця мухомора"),
         "death_cap": ("Death Cap Mycelium", "Грибниця блідої поганки"),
         "inky_cap": ("Inky Cap Mycelium", "Грибниця гнойовика"),
         "mycena_chlorophos": ("Mycena Chlorophos Mycelium", "Грибниця мицени хлорофос")}
for sid in SPECIES:
    b = f"{sid}_mycelium"
    for tier in range(3):
        w(f"{R}/assets/{MOD}/models/block/{b}_{tier}.json", {
            "parent": "minecraft:block/cube_bottom_top",
            "textures": {"top": f"{MOD}:block/{sid}_mycelium_top_{tier}",
                         "side": f"{MOD}:block/mycelium_side_{tier}",
                         "bottom": "minecraft:block/dirt"}})
    w(f"{R}/assets/{MOD}/blockstates/{b}.json", {"variants": {
        f"vigor={v}": {"model": f"{MOD}:block/{b}_{tier_of(v)}"} for v in range(8)}})
    w(f"{R}/assets/{MOD}/models/item/{b}.json", {"parent": f"{MOD}:block/{b}_2"})
    # Silk Touch забирає грибницю цілою, інакше випадає земля
    w(f"{R}/data/{MOD}/loot_tables/blocks/{b}.json", {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [
        {"type": "minecraft:alternatives", "children": [
            {"type": "minecraft:item", "name": f"{MOD}:{b}", "conditions": [
                {"condition": "minecraft:match_tool", "predicate": {"enchantments": [
                    {"enchantment": "minecraft:silk_touch", "levels": {"min": 1}}]}}]},
            {"type": "minecraft:item", "name": "minecraft:dirt"}]}]}]})
    # посів: гриб + субстрат = шматок грибниці
    w(f"{R}/data/{MOD}/recipes/{b}.json", {"type": "minecraft:crafting_shapeless", "category": "misc",
        "ingredients": [{"item": f"{MOD}:{sid}"}, {"item": f"{MOD}:mushroom_substrate"}],
        "result": {"item": f"{MOD}:{b}"}})
    en2[f"block.{MOD}.{b}"], uk2[f"block.{MOD}.{b}"] = NAMES[sid]
    k = f"tooltip.{MOD}.{b}"
    en2[f"{k}.1"], uk2[f"{k}.1"] = "A living network. A ripe mushroom on top feeds it.", "Живий організм. Його годує стиглий гриб згори."
    if sid in ("inky_cap", "morel", "mycena_chlorophos"):
        en2[f"{k}.2"], uk2[f"{k}.2"] = "Devours plants around it: grass, flowers, saplings.", "Поглинає рослини навколо: траву, квіти, саджанці."
    else:
        en2[f"{k}.2"], uk2[f"{k}.2"] = "Lives in tree roots, feeds on water and soil. Dies without trees.", "Живе в корінні дерев, живиться водою й ґрунтом. Без дерев гине."
    en2[f"{k}.3"], uk2[f"{k}.3"] = "Spreads over soil, fruits at its edge. Silk Touch moves it.", "Захоплює ґрунт, плодоносить на краю. Шовковий дотик переносить."
merge_lang("en_us", en2); merge_lang("uk_ua", uk2)

# перевірка JSON
for root, _, files in os.walk(R):
    for f in files:
        if f.endswith(".json"): json.load(open(os.path.join(root, f), encoding="utf-8"))
print("gardening assets done")
