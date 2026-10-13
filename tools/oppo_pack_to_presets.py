#!/usr/bin/env python
"""
pack.json (OPPO COE 场景图) -> presets.json (运行时精简预设表)

用法:
  # 源 pack.json 不进 APK，只留在 refs/；产物写在 assets 下
  python oppo_pack_to_presets.py <pack.json> <输出 presets.json>

  例:
  python oppo_pack_to_presets.py \
      "D:/01 项目/100 个人文件/天气/refs/v99_pack/skypulse_v99_animation_rebuild/01_oppo_weather完整资源包/pack.json" \
      "app/src/main/assets/oppo/presets.json"

做三件事:
  1. 把 sharedTexture/<hash> 解析为实际文件路径（textures/...），运行时不必再查表；
  2. 丢弃编辑器残留（guid / material.key / animations / originalPath / sha256 / 冗余 blend 字段）；
  3. 从实体名里解析内嵌参数（云组/编号/槽位/alpha/偏移、太阳 halo 层级与缩放）；
  4. 实体内容去重成共享池，预设只存下标。

坐标系说明（从数据反推，已核对）：
  x ∈ [-1, 1]（左→右），y ∈ [-s, +s]（上为正），s = 屏幕高/宽
  依据: baseline=1080x2400 → s = 2400/1080 = 2.2222…，与 background 实体的 scale.y 完全一致
  （单位四边形为 2x2，scale=(1,2.2222) 后纵跨 ±2.2222 正好铺满全屏）。
"""
import json
import os
import re
import sys

CLOUD_RE = re.compile(r"^cloud_([a-z])(\d+)_(\d+)_([\d.]+)_(-?[\d.]+)_(-?[\d.]+)$")
SUN_HALO_RE = re.compile(r"^sun_halo(\d+)_([\d.]+)$")
SUN_MAIN_RE = re.compile(r"^sun_main_([\d.]+)$")
SUN_POINT_RE = re.compile(r"^sun_point(_(start|top|end))?$")
THUNDER_RE = re.compile(r"^thunder_(\d+)$")
PARTICLE_RE = re.compile(r"^particle_(\w+)$")

KEEP_BLEND = ("srcColorBlendFactor", "dstColorBlendFactor", "srcAlphaBlendFactor", "dstAlphaBlendFactor")


def vec(v, n=None):
    """Unity 风格的 {x,y,z} 或数组 -> 数组"""
    if isinstance(v, dict):
        out = [v.get("x", 0), v.get("y", 0), v.get("z", 0)]
    elif isinstance(v, list):
        out = list(v)
    else:
        return v
    if n is not None:
        out = (out + [0] * n)[:n]
    return out


# 粒子精灵：pack.json 里写的是 `material/<uuid>/<role>`，指向未导出的 materials 表 → 解析不了。
# 但 44 张贴图里有同名角色的粒子精灵，按角色名兜底（这是**替代映射**，不是原引用）。
PARTICLE_SPRITE_BY_ROLE = {
    "rain": "textures/particle/rain-p.png",
    "rainfront": "textures/particle/rain-p.png",
    "snow": "textures/particle/snow-p.png",
    "star": "textures/particle/star3.png",
}

# 全局统计：有多少贴图引用最终没能解析（供人工核对）
UNRESOLVED = []


def resolve_textures(tex, texmap):
    """{'u_NormalTex': 'sharedTexture/xxx'} -> {'u_NormalTex': 'textures/cloud_b/b_02_normal.png'}

    以 `material/` 开头的引用（粒子的 `rain`/`snow`）走 [PARTICLE_SPRITE_BY_ROLE] 兜底。
    """
    out = {}
    for role, ref in (tex or {}).items():
        info = texmap.get(ref)
        if info and info.get("file"):
            out[role] = info["file"]
            continue
        path = str(ref or "")
        if path.startswith("material/"):
            # 形如 material/<uuid>/rain —— 末段就是角色名
            tail = path.rsplit("/", 1)[-1]
            mapped = PARTICLE_SPRITE_BY_ROLE.get(tail)
            if mapped:
                out[role] = mapped
                continue
        UNRESOLVED.append(path)
        out[role] = path
    return out


def blend_of(material):
    try:
        att = material["blend"]["attachments"][0]
    except (KeyError, IndexError, TypeError):
        return None
    b = {k: att.get(k, {}).get("value") for k in KEEP_BLEND if isinstance(att.get(k), dict)}
    return b or None


def entity_of(e, texmap):
    t = e.get("transform") or {}
    m = e.get("material") or {}
    out = {
        "name": e.get("name", ""),
        "sort": e.get("sort", 0),
        "visible": bool(e.get("visible", True)),
        "pos": vec(t.get("position"), 3),
        "scale": vec(t.get("scale"), 3),
        "rot": vec(t.get("rotation"), 3),
    }
    if m:
        out["shaderVert"] = m.get("shaderVert")
        out["shaderFrag"] = m.get("shaderFrag")
        u = m.get("uniforms") or {}
        if u:
            out["uniforms"] = u
        tex = resolve_textures(m.get("textures"), texmap)
        if tex:
            out["textures"] = tex
        bl = blend_of(m)
        if bl:
            out["blend"] = bl

    name = out["name"]
    if (mm := CLOUD_RE.match(name)):
        out["cloud"] = {
            "group": mm.group(1), "id": mm.group(2), "slot": mm.group(3),
            "alpha": float(mm.group(4)), "px": float(mm.group(5)), "py": float(mm.group(6)),
        }
    elif (mm := SUN_HALO_RE.match(name)):
        out["sunHalo"] = {"level": int(mm.group(1)), "scale": float(mm.group(2))}
    elif (mm := SUN_MAIN_RE.match(name)):
        out["sunMain"] = {"scale": float(mm.group(1))}
    elif SUN_POINT_RE.match(name):
        out["sunPoint"] = True
    elif (mm := THUNDER_RE.match(name)):
        out["thunder"] = {"frame": int(mm.group(1))}
    elif (mm := PARTICLE_RE.match(name)):
        # 粒子配置**整段在 pack.json 里**（不在别的 materials 表里，那个表压根没导出）。
        # 早期版本只留了 role，把最关键的发射参数丢了 —— 这里完整搬运：
        #   compute    主发射 compute shader（particle/<guid>.cs）
        #   uniforms   activeParticeNumber / emitterPosition / emitterSize / depthRatio /
        #              speed / lifeTime / sizeAndNoise / 三档景深(close|mid|far)* / direcMin|Max …
        #   effects    追加的 compute 通道（风、湍流），各自带 uniforms 与 .cs 路径
        #   textures   通常为空；粒子精灵靠 material.textures 的 role 名（rain/snow）对应素材
        p = e.get("particle") or {}
        out["particle"] = {
            "role": mm.group(1),
            "compute": p.get("computeSourcePath"),
            "uniforms": p.get("uniforms") or {},
            "textures": p.get("textures") or {},
            "effects": [
                {
                    "compute": x.get("computeSourcePath"),
                    "uniforms": x.get("uniforms") or {},
                    "textures": x.get("textures") or {},
                }
                for x in (p.get("effects") or [])
            ],
        }
    return out


def lights_of(raw):
    amb, dirs, pt = None, [], None
    for L in (raw or []):
        typ = L.get("type")
        if typ == "AmbientLight":
            amb = {"color": vec(L.get("color"), 3), "intensity": L.get("intensity", 1.0)}
        elif typ == "DirectionalLight":
            dirs.append({
                "name": L.get("name"),
                "dir": vec(L.get("direction"), 3),
                "color": vec(L.get("color"), 3),
                "intensity": L.get("intensity", 1.0),
                "enabled": bool(L.get("enabled", True)),
            })
        elif typ == "PointLight":
            pt = {
                "name": L.get("name"),
                "pos": vec(L.get("position"), 3),
                "color": vec(L.get("color"), 3),
                "intensity": L.get("intensity", 1.0),
                # shader 里 `float range = light.range / 200.`，原数据是 0~几千的绝对量纲，
                # 不能丢——丢了会除零。
                "range": L.get("range", 0.0),
                "attenuation": vec(L.get("attenuation"), 3),
                "enabled": bool(L.get("enabled", True)),
            }
    return {"ambient": amb, "directional": dirs, "point": pt}


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    src, dst = sys.argv[1], sys.argv[2]
    with open(src, encoding="utf-8") as f:
        d = json.load(f)

    texmap = d.get("textures") or {}
    out = {
        "version": 1,
        "source": d.get("source"),
        "baseline": d.get("baseline"),
        "coords": {
            "note": "x in [-1,1]; y in [-s,+s] where s = viewportHeight/viewportWidth; y up",
            "baselineS": round((d.get("baseline") or [1080, 2400])[1] / (d.get("baseline") or [1080, 2400])[0], 6),
        },
        "entities": [],
        "presets": {},
    }

    # 实体去重池：多个预设大量共享同一批云/雷/粒子实体（如雨天与雷暴共用同一组云），
    # 按规范化 JSON 做内容指纹，预设只存下标。
    pool, index = [], {}
    dedup_saved = 0

    for key, s in (d.get("scenes") or {}).items():
        ents = [entity_of(e, texmap) for e in (s.get("entities") or [])]
        ents.sort(key=lambda x: x["sort"])
        idxs = []
        for e in ents:
            fp = json.dumps(e, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            i = index.get(fp)
            if i is None:
                i = len(pool)
                index[fp] = i
                pool.append(e)
            else:
                dedup_saved += 1
            idxs.append(i)
        out["presets"][key] = {"lighting": lights_of(s.get("lights")), "entityIdx": idxs}

    out["entities"] = pool

    # 贴图元数据表（按解析后的文件路径索引）：采样方式与尺寸。
    # wrap 必须带上——云用的是 CLAMP_TO_EDGE、太阳光晕是 REPEAT，混了会出接缝或边缘拉丝。
    tex_meta = {}
    for info in texmap.values():
        f = info.get("file")
        if not f:
            continue
        tex_meta[f] = {
            "wrap": "REPEAT" if str(info.get("wrap", "")).upper().endswith("REPEAT") else "CLAMP_TO_EDGE",
            "w": info.get("width"),
            "h": info.get("height"),
        }
    out["textures"] = tex_meta

    os.makedirs(os.path.dirname(os.path.abspath(dst)), exist_ok=True)
    with open(dst, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))

    print(f"presets: {len(out['presets'])}  实体池: {len(pool)}  去重省下引用: {dedup_saved}")
    if UNRESOLVED:
        from collections import Counter
        c = Counter(UNRESOLVED)
        print(f"⚠️ 未解析贴图引用 {len(UNRESOLVED)} 处（去重后 {len(c)} 种）:")
        for k, n in c.most_common(10):
            print(f"    {k}  ×{n}")
    print(f"wrote {dst}  {os.path.getsize(dst) / 1024:.0f} KB (源 {os.path.getsize(src) / 1024:.0f} KB)")


if __name__ == "__main__":
    main()
