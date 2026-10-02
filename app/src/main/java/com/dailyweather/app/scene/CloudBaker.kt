package com.dailyweather.app.scene

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color

/**
 * 把一张白模云按光照档烘焙成该场景下的云。
 *
 * 输入是 `gray+alpha` 的剪影：灰度 `l` 是云体自身的**光学厚度代理**
 * （白=薄/受光面，灰=厚/自阴影），alpha 是形状。输出保留 alpha，重算 RGB。
 *
 * 四组光按加色合成，全部在 0–1 线性量上算完再夹回：
 * ```
 * sky     = 天空填充   × 云体厚度            （整体基调）
 * ambient = 环境光     × 恒定                 （明度下限）
 * ground  = 地面反弹   × (1 - 厚度)           （主要落在云底）
 * sun     = 太阳       × 朝向项 × 边缘朝向项   （唯一高光，出金边）
 * ```
 * 边缘朝向项来自灰度的**垂直梯度**：`dL/dy > 0` 表示上亮下暗，即该处表面朝上，
 * 吃得到太阳。这样一张纯白剪影也能烘出"顶部被照亮、底部压暗"的体积感，
 * 而不必让美术出第二张法线图。
 *
 * 成本：单张 720×404 ≈ 29 万像素，纯 Kotlin 像素循环。烘焙结果按
 * [CloudBakeKey] 缓存，**一场景一物种只算一次**，所以这个成本发生在启动预热
 * 而不是每帧。实测耗时由 [CloudBakeCache] 统计并显示在烘焙实验页。
 */
object CloudBaker {

    fun bake(src: Bitmap, rig: CloudLightRig): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val op = IntArray(w * h)

        // 天空填充按 overcast 在"晴"与"阴"两档之间插值 —— 阴天不需要新素材。
        val sky = lerpColor(rig.skyFill, rig.overcastSkyFill, rig.overcast)
        val skyR = sky.red; val skyG = sky.green; val skyB = sky.blue
        val ambR = rig.ambient.red; val ambG = rig.ambient.green; val ambB = rig.ambient.blue
        val gndR = rig.ground.red; val gndG = rig.ground.green; val gndB = rig.ground.blue
        val sunR = rig.sun.red; val sunG = rig.sun.green; val sunB = rig.sun.blue

        // 太阳方向：+1 正午（整体提亮、无金边），0 地平线（金边最强），-1 落下。
        // 用 |sunDir| 压低"整体受光"，用 (1-|sunDir|) 抬起"边缘优先受光"。
        val flatLight = 1f - kotlin.math.abs(rig.sunDir)
        val edgeBias = 0.45f + 0.55f * flatLight

        for (y in 0 until h) {
            val row = y * w
            // 垂直梯度取上下各 2 行，比相邻行稳，避免 1px 噪声被放大成锯齿。
            val yUp = if (y >= 2) row - 2 * w else row
            val yDn = if (y <= h - 3) row + 2 * w else row
            for (x in 0 until w) {
                val p = px[row + x]
                val a = (p ushr 24) and 0xFF
                if (a == 0) { op[row + x] = 0; continue }

                val l = ((p ushr 16) and 0xFF) / 255f

                val lu = ((px[yUp + x] ushr 16) and 0xFF) / 255f
                val ld = ((px[yDn + x] ushr 16) and 0xFF) / 255f
                // 上亮下暗 → 朝上 → 吃太阳。乘 4 是把素材里 0.1 量级的梯度抬到可用区间。
                val slope = ((lu - ld) * 4f).coerceIn(-1f, 1f)

                val upness = l
                val skyAmt = 0.35f + 0.65f * upness
                val gndAmt = (1f - upness) * rig.groundStrength
                val sunFace = (0.55f * upness + 0.45f * (0.5f + 0.5f * slope * edgeBias + 0.5f * flatLight))
                    .coerceIn(0f, 1f) * rig.sunStrength
                val ambAmt = rig.ambientStrength

                var r = skyR * skyAmt + ambR * ambAmt + gndR * gndAmt + sunR * sunFace
                var g = skyG * skyAmt + ambG * ambAmt + gndG * gndAmt + sunG * sunFace
                var b = skyB * skyAmt + ambB * ambAmt + gndB * gndAmt + sunB * sunFace

                // 加色合成必然溢出，按整体等比压回而不是逐通道截断，
                // 否则高光会被削成纯白一片、丢掉云的层次。
                val m = maxOf(r, g, b)
                if (m > 1f) { val inv = 1f / m; r *= inv; g *= inv; b *= inv }

                op[row + x] = (a shl 24) or
                    ((r * 255f + 0.5f).toInt() shl 16) or
                    ((g * 255f + 0.5f).toInt() shl 8) or
                    (b * 255f + 0.5f).toInt()
            }
        }
        out.setPixels(op, 0, w, 0, 0, w, h)
        return out
    }

    private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
    )
}
