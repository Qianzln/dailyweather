#!/usr/bin/env python
"""
从南风 v4.3.99 的 `w7/p0.java` 提取两张静态表：

1. **palettes**：场景键 → (segments[3], 5 个颜色)。实测与 pack.json 里 background 实体的
   `_Segment1..3` / `_Color0..4` **完全一致**（已逐值核对 sunny-noon），所以它主要是交叉校验用；
   南风把它抽出来是为了给 Compose 侧（玻璃色/UI 主题）用。
2. **groups**：组号 → { 时段槽位 → 场景键 }。这是南风自己的映射表（`p0.java` 静态块里自动派生），
   是 `timeSlot → sceneKey` 解析的权威依据。

用法:
  python oppo_extract_p0_tables.py <p0.java> <输出 scene_map.json>
"""
import json
import os
import re
import sys

# e8.y.a("KEY", new Palette(new float[]{0.25f, 0.5f, 0.75f}, kotlin.collections.v.o(
KEY_RE = re.compile(r'e8\.y\.a\("([^"]+)",\s*new Palette\(new float\[\]\{([^}]*)\}')
# x1.e(0.1254902f, 0.3254902f, 0.72156864f, 1.0f, null, 16, null)
COLOR_RE = re.compile(r'x1\.e\(([\d.]+)f,\s*([\d.]+)f,\s*([\d.]+)f,\s*1\.0f')


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    src, dst = sys.argv[1], sys.argv[2]
    text = open(src, encoding="utf-8").read()

    palettes = {}
    matches = list(KEY_RE.finditer(text))
    for i, m in enumerate(matches):
        key = m.group(1)
        segments = [float(x.strip().rstrip("f")) for x in m.group(2).split(",") if x.strip()]
        # 颜色取到下一个 key 之前
        end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        colors = [[float(a), float(b), float(c), 1.0] for a, b, c in COLOR_RE.findall(text[m.end():end])]
        palettes[key] = {"segments": segments, "colors": colors}

    # groups：与 p0.java 静态块同样的派生方式 —— 键名第一个 '_' 前那段作为组号
    groups = {}
    for key in palettes:
        head = key.split("_", 1)[0]
        if not head.isdigit():
            continue
        slot = int(key.split("_")[1])
        groups.setdefault(int(head), {})[slot] = key

    out = {
        "source": "南风 v4.3.99 w7/p0.java 静态块（groups 由 palettes 键派生，与源码同法）",
        "paletteCount": len(palettes),
        "groupCount": len(groups),
        "palettes": palettes,
        "groups": {str(k): {str(s): v for s, v in slots.items()} for k, slots in sorted(groups.items())},
    }
    os.makedirs(os.path.dirname(os.path.abspath(dst)), exist_ok=True)
    with open(dst, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1, sort_keys=True)

    print(f"palettes: {len(palettes)}   groups: {len(groups)}")
    for g, slots in sorted(groups.items()):
        print(f"  组 {g:>3}: 时段 {sorted(slots)}  →  {list(slots.values())[0]}")
    print(f"wrote {dst}  {os.path.getsize(dst) / 1024:.0f} KB")


if __name__ == "__main__":
    main()
