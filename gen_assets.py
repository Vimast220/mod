import json, os
from PIL import Image

MOD = "realshrooms"
R = "src/main/resources"

def w(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        if isinstance(data, str): f.write(data)
        else: json.dump(data, f, ensure_ascii=False, indent=2)

# ---------- pixel art (останній рядок = земля) ----------
def hexc(h): h=h.lstrip('#'); return tuple(int(h[i:i+2],16) for i in (0,2,4))+(255,)

ART = {
 "chanterelle": ({'o':'F5C04A','O':'E39A1B','d':'B8700F','s':'F0D9A0','S':'D4B676'}, [
  "oo.oOOo.oo","oOOOOOOOOo","dOOOOOOOOd",".dOOOOOOd.","..dOOOOd..",
  "...sOOs...","....sS....","....ss....","....sS....","...ssS...."]),
 "porcini": ({'b':'B07A42','B':'8B5A2B','D':'5E3A1A','w':'EBDDBF','W':'CDBA94'}, [
  "..bbbbbb..",".bbBBBBBb.","bBBBBBBBBD","BBBBBBBBDD",".wwwwwwww.",
  "...wwww...","...wwWw...","...wwWW...","...wwww...","..wwWWww.."]),
 "morel": ({'t':'D2AE6E','T':'B08B4F','P':'5A4020','s':'E8DCC0','S':'CBBE9E'}, [
  "....tt....","...tPtT...","..tPTPtT..","..TPtPTP..",".tPTPtPTt.",
  ".TtPTPtPT.","...ssss...","...sSss...","...ssSs...","...ssss..."]),
 "fly_agaric": ({'r':'E5453B','R':'C8201E','d':'8E1412','W':'F4F1E8','g':'D8D3C4'}, [
  "...rrrr...",".rrRRWRRr.","rRRWRRRWRd","RRRRRRRRRd","RWRRRWRRRd",
  "dRRRRRRRdd","..dddddd..","....WW....","...WWWW...","....WgW...","....WgW...","...WWgWW.."]),
 "death_cap": ({'g':'A7AC50','G':'8A8F3A','d':'5E6226','w':'E9E7CF','W':'CFCDB0'}, [
  "..gggggg..",".gGGGGGGg.","gGGGGGGGGd","GGGGGGGGdd",".dddddddd.",
  "....ww....","...wwww...","....wW....","....wW....","...wwWw...","..wwwWww.."]),
 "inky_cap": ({'c':'DADAD2','C':'B5B5AC','k':'3A3A3A','w':'F0F0EA','W':'CFCFC8'}, [
  "....cc....","...cccc...","...cCcC...","..cCCCCc..","..cCCCCC..",
  ".kCCCCCCk.",".k..kk..k.","....wW....","....wW....","....wW....","....wW....","...wwWW..."]),
 "cooked_morel": ({'t':'A87A45','T':'7A5230','P':'3A2410','s':'BFA27A','S':'9C8260'}, [
  "....tt....","...tPtT...","..tPTPtT..","..TPtPTP..",".tPTPtPTt.",
  ".TtPTPtPT.","...ssss...","...sSss...","...ssSs...","...ssss..."]),
}

# Мицена хлорофос: дві крихітні плодові тіла, напівпрозорі ніжки, світні пластинки (блакитне сяйво).
ART["mycena_chlorophos"] = ({'d':'8F887B','C':'B9B1A4','c':'D8D2C6','g':'46C8FF','G':'B4EBFF','s':'E6EEF2','S':'BFCBD2'}, [
  "...dcd......",
  "..dCcCd..dd.",
  ".dCCCCCd.CcC",
  ".gGgGgGg.gGg",
  "....s.....s.",
  "....S.....s.",
  "....s.....S.",
  "....s.....s.",
  "....s.....s.",
  "...ssS...ss.",
  "..ssSss.sss."])


# Опеньок осінній: купка медово-жовтих грибів із темними шапками-«лусочками» та ніжками з кільцем.
ART["honey_fungus"] = ({'h':'EBCD6E','H':'D6AA46','d':'826028','s':'BE9F6E','S':'967850'}, [
  "..hHh...hh..",
  ".hHHHh.hHHh.",
  "dHHHHHdHHHHd",
  ".dddd..dddd.",
  "..sS....sS..",
  "..sS...ssS..",
  "..sS...sS...",
  ".ssSs.ssSs.."])

def render(rows, pal, bottom):
    img = Image.new("RGBA",(16,16),(0,0,0,0))
    top = 16-len(rows) if bottom else (16-len(rows))//2
    for y,row in enumerate(rows):
        row = row.center(16,'.')
        for x,ch in enumerate(row):
            if ch in pal: img.putpixel((x,top+y), hexc(pal[ch]))
    return img

sheet = Image.new("RGBA",(16*len(ART)*8+8*len(ART), 16*8),(60,110,60,255))
for i,(name,(pal,rows)) in enumerate(ART.items()):
    item = render(rows,pal,False)
    os.makedirs(f"{R}/assets/{MOD}/textures/item",exist_ok=True)
    item.save(f"{R}/assets/{MOD}/textures/item/{name}.png")
    if name!="cooked_morel":
        os.makedirs(f"{R}/assets/{MOD}/textures/block",exist_ok=True)
        render(rows,pal,True).save(f"{R}/assets/{MOD}/textures/block/{name}.png")
    sheet.alpha_composite(item.resize((128,128),Image.NEAREST),(i*136,0))
sheet.save("preview.png")

# ---------- ресурси ----------
SPECIES = {  # id: (rarity chance, tries, en, uk)
 "chanterelle": (4, 20, "Chanterelle", "Лисичка"),
 "porcini":     (5, 18, "Porcini", "Білий гриб"),
 "morel":       (12, 12, "Morel", "Зморшок"),
 "fly_agaric":  (6, 16, "Fly Agaric", "Мухомор червоний"),
 "death_cap":   (9, 12, "Death Cap", "Бліда поганка"),
 "inky_cap":    (5, 16, "Inky Cap", "Чорнильний гнойовик"),
 "mycena_chlorophos": (3, 30, "Mycena Chlorophos", "Мицена хлорофос"),
 "honey_fungus": (9, 14, "Honey Fungus", "Опеньок осінній"),
}
en = {}; uk = {}
for sid,(rar,tries,e,u) in SPECIES.items():
    ref = f"{MOD}:{sid}"
    w(f"{R}/assets/{MOD}/blockstates/{sid}.json", {"variants":{"":{"model":f"{MOD}:block/{sid}"}}})
    w(f"{R}/assets/{MOD}/models/block/{sid}.json", {"parent":"minecraft:block/cross","textures":{"cross":f"{MOD}:block/{sid}"}})
    w(f"{R}/assets/{MOD}/models/item/{sid}.json", {"parent":"minecraft:item/generated","textures":{"layer0":f"{MOD}:item/{sid}"}})
    w(f"{R}/data/{MOD}/loot_tables/blocks/{sid}.json", {"type":"minecraft:block","pools":[{"rolls":1,"entries":[{"type":"minecraft:item","name":ref}],"conditions":[{"condition":"minecraft:survives_explosion"}]}]})
    w(f"{R}/data/{MOD}/worldgen/configured_feature/patch_{sid}.json", {
      "type":"minecraft:random_patch",
      "config":{"tries":tries,"xz_spread":6,"y_spread":2,"feature":{
        "feature":{"type":"minecraft:simple_block","config":{"to_place_state":None}},
        "placement":[]}}})
    # simple_block у 1.20.1 використовує ключ to_place
    cf = {"type":"minecraft:random_patch","config":{"tries":tries,"xz_spread":6,"y_spread":2,"feature":{
        "feature":{"type":"minecraft:simple_block","config":{"to_place":{"type":"minecraft:simple_state_provider","state":{"Name":ref}}}},
        "placement":[{"type":"minecraft:block_predicate_filter","predicate":{"type":"minecraft:all_of","predicates":[
            {"type":"minecraft:matching_blocks","blocks":"minecraft:air"},
            {"type":"minecraft:would_survive","state":{"Name":ref}}]}}]}}}
    w(f"{R}/data/{MOD}/worldgen/configured_feature/patch_{sid}.json", cf)
    w(f"{R}/data/{MOD}/worldgen/placed_feature/{sid}.json", {
      "feature":f"{MOD}:patch_{sid}",
      "placement":[{"type":"minecraft:rarity_filter","chance":rar},{"type":"minecraft:in_square"},
                   {"type":"minecraft:heightmap","heightmap":"MOTION_BLOCKING_NO_LEAVES"},{"type":"minecraft:biome"}]})
    en[f"block.{MOD}.{sid}"]=e; en[f"item.{MOD}.{sid}"]=e
    uk[f"block.{MOD}.{sid}"]=u; uk[f"item.{MOD}.{sid}"]=u
en[f"item.{MOD}.cooked_morel"]="Cooked Morel"; uk[f"item.{MOD}.cooked_morel"]="Смажений зморшок"
w(f"{R}/assets/{MOD}/lang/en_us.json", en); w(f"{R}/assets/{MOD}/lang/uk_ua.json", uk)
w(f"{R}/assets/{MOD}/models/item/cooked_morel.json", {"parent":"minecraft:item/generated","textures":{"layer0":f"{MOD}:item/cooked_morel"}})

# ---------- рецепти ----------
rc = f"{R}/data/{MOD}/recipes"
for kind,t,time in (("smelting","minecraft:smelting",200),("smoking","minecraft:smoking",100),("campfire","minecraft:campfire_cooking",600)):
    w(f"{rc}/cooked_morel_{kind}.json", {"type":t,"category":"food","ingredient":{"item":f"{MOD}:morel"},"result":f"{MOD}:cooked_morel","experience":0.1,"cookingtime":time})
w(f"{rc}/ink_sac_from_inky_cap.json", {"type":"minecraft:crafting_shapeless","category":"misc","ingredients":[{"item":f"{MOD}:inky_cap"},{"item":f"{MOD}:inky_cap"}],"result":{"item":"minecraft:ink_sac"}})
w(f"{rc}/forest_mushroom_stew.json", {"type":"minecraft:crafting_shapeless","category":"food","ingredients":[{"item":"minecraft:bowl"},{"item":f"{MOD}:chanterelle"},{"item":f"{MOD}:porcini"}],"result":{"item":"minecraft:mushroom_stew"}})
print("done")
