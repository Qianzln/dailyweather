package com.dailyweather.app.scene

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 云精灵的烘焙缓存 —— 对应南风的 `cloudSpriteSetsCache` /
 * `CloudSpriteSet$Companion$fromResource$baked` / `rememberCloudSpriteSet`。
 *
 * 三条设计约束：
 * 1. **按 [CloudBakeKey] 缓存，键里不含显示尺寸** —— 一律烘到素材原始分辨率，
 *    绘制时再缩放。否则每个卡片尺寸都要重烘一次，命中率会很难看。
 * 2. 烘焙**只在后台线程**发生；组合阶段读不到就返回空集，绝不卡主线程。
 * 3. 统计烘焙次数与耗时，直接显示在烘焙实验页 —— 这一版结论必须用真机数字说话，
 *    不能停在"应该不慢"。
 */
object CloudSpriteCache {

    /** 烘焙结果上限。单张 720×404 ARGB ≈ 1.1 MB，64 MB 够放约 55 张。 */
    private const val MAX_BYTES = 64L * 1024 * 1024

    /** accessOrder = true：迭代到的第一个就是最久未用，直接作为逐出对象。 */
    private val entries = LinkedHashMap<CloudBakeKey, ImageBitmap>(16, 0.75f, true)
    private val decoded = HashMap<Int, Bitmap>()
    private var bytes = 0L

    var bakeCount = 0; private set
    var bakeMillis = 0L; private set
    var decodeMillis = 0L; private set

    /** 最近一次单张烘焙耗时，实验页用它做横向对比。 */
    var lastBakeMillis = 0L; private set

    @Synchronized
    fun peek(key: CloudBakeKey): ImageBitmap? = entries[key]

    @Synchronized
    fun snapshot(): CloudCacheStats =
        CloudCacheStats(entries.size, bytes, bakeCount, bakeMillis, decodeMillis, lastBakeMillis)

    @Synchronized
    fun clear() {
        entries.clear(); decoded.clear(); bytes = 0
    }

    /**
     * 取一张**未经烘焙**的原图（雨丝这类小素材不需要重打光，绘制时 tint 即可）。
     * 解码结果按 resId 复用，返回的是同一个 ImageBitmap 实例。
     */
    private val raws = HashMap<Int, ImageBitmap>()

    @Synchronized
    fun raw(context: Context, resId: Int): ImageBitmap? {
        raws[resId]?.let { return it }
        val bmp = runCatching { decodeSource(context, resId) }.getOrNull() ?: return null
        return bmp.asImageBitmap().also { raws[resId] = it }
    }

    @Synchronized
    private fun decodeSource(context: Context, resId: Int): Bitmap {
        decoded[resId]?.let { if (!it.isRecycled) return it }
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            // 素材是 nodpi 的绝对像素图，禁止按屏幕密度缩放，否则烘焙基准尺寸随机型漂移。
            inScaled = false
        }
        val t0 = System.nanoTime()
        val bmp = BitmapFactory.decodeResource(context.resources, resId, opts)
            ?: throw IllegalStateException("云素材解码失败：resId=$resId")
        decodeMillis += (System.nanoTime() - t0) / 1_000_000L
        decoded[resId] = bmp
        return bmp
    }

    /**
     * 烘焙并入库；已缓存则直接返回缓存，不做二次像素运算。
     *
     * **像素运算刻意放在锁外。** 上一版整个方法持锁，结果启动预热（几十张 × 数十毫秒）
     * 把锁占住，首页的 `rememberCloudSpriteSet` 在等同一把锁 —— 云迟迟出不来，
     * 观感上像"预热反而拖慢了首屏"。现在只在查表和写表时短暂持锁。
     *
     * 代价：并发请求同一个 key 时会重复烘一次（结果一致，后写覆盖先写）。
     * 这是可接受的 —— 重复烘只是白算，饿死 UI 是看得见的 bug。
     */
    fun bake(context: Context, species: CloudSpecies, shapeIndex: Int, rig: CloudLightRig): ImageBitmap {
        val key = CloudBakeKey(species, shapeIndex, rig.bakeQuantum())
        peek(key)?.let { return it }

        val resId = species.shapes[shapeIndex.mod(species.shapes.size)]
        val t0 = System.nanoTime()
        val bitmap: Bitmap = if (species.isMask) {
            CloudBaker.bake(decodeSource(context, resId), rig)
        } else {
            // 照片级素材不重打光：它自带暖顶冷底，再叠一层加法会把压暗的云底洗白。
            decodeSource(context, resId)
        }
        val cost = (System.nanoTime() - t0) / 1_000_000L
        store(key, bitmap.asImageBitmap(), bitmap.byteCount.toLong(), cost)
        return peek(key) ?: bitmap.asImageBitmap()
    }

    @Synchronized
    private fun store(key: CloudBakeKey, image: ImageBitmap, byteSize: Long, costMillis: Long) {
        if (entries.containsKey(key)) return // 别人先烘完了，不重复计数
        entries[key] = image
        bytes += byteSize
        bakeCount += 1
        bakeMillis += costMillis
        lastBakeMillis = costMillis
        while (bytes > MAX_BYTES && entries.isNotEmpty()) {
            val it = entries.entries.iterator()
            if (!it.hasNext()) break
            val evicted = it.next()
            bytes -= evicted.value.width.toLong() * evicted.value.height * 4L
            it.remove()
        }
    }
}

data class CloudCacheStats(
    val entries: Int,
    val bytes: Long,
    val bakeCount: Int,
    val bakeMillis: Long,
    val decodeMillis: Long,
    val lastBakeMillis: Long,
) {
    val avgBakeMillis: Long get() = if (bakeCount == 0) 0L else bakeMillis / bakeCount
    val megabytes: Float get() = bytes / 1024f / 1024f
}

/** 一个物种在当前光照档下可用的精灵集合。 */
class CloudSpriteSet(
    val species: CloudSpecies,
    val images: List<ImageBitmap>,
) {
    val ready: Boolean get() = images.isNotEmpty()
    fun at(index: Int): ImageBitmap = images[index.mod(images.size)]
}

/** 对应南风的 `rememberCloudSpriteSet`：光照档变了就重烘（命中缓存则零成本）。 */
@Composable
fun rememberCloudSpriteSet(
    context: Context,
    species: CloudSpecies,
    rig: CloudLightRig,
): CloudSpriteSet {
    val shapes = remember(species) { (0 until species.shapes.size).toList() }
    val images by produceState<List<ImageBitmap>>(initialValue = emptyList(), species, rig, shapes) {
        value = withContext(Dispatchers.Default) {
            shapes.map { CloudSpriteCache.bake(context, species, it, rig) }
        }
    }
    return remember(images, species) { CloudSpriteSet(species, images) }
}

/** 预热结果，用于启动日志与实验页展示。 */
data class BakeReport(
    val baked: Int,
    val millis: Long,
    val failures: List<String>,
)

/**
 * 启动预热 —— 对应南风「天气粒子资源预热失败」那条日志所暗示的预热步骤。
 *
 * 把四套时段光照 × 各物种形状全部烘好，之后场景切换只读缓存。
 * 失败不抛异常：预热是观感优化，不该让首页起不来。
 */
suspend fun prewarmCloudSprites(
    context: Context,
    rigs: List<CloudLightRig>,
    species: List<CloudSpecies> = CloudSpecies.values().toList(),
): BakeReport = withContext(Dispatchers.Default) {
    var count = 0
    val failed = mutableListOf<String>()
    val t0 = System.nanoTime()
    for (rig in rigs) {
        for (s in species) {
            for (i in s.shapes.indices) {
                try {
                    CloudSpriteCache.bake(context, s, i, rig)
                    count += 1
                } catch (e: Throwable) {
                    if (failed.size < 4) failed += "${s.name}#$i: ${e.message}"
                }
            }
        }
    }
    val report = BakeReport(count, (System.nanoTime() - t0) / 1_000_000L, failed)
    if (failed.isNotEmpty()) Log.w(TAG, "天气云资源预热部分失败：$failed")
    Log.i(TAG, "云资源预热：${report.baked} 张 / ${report.millis} ms")
    return@withContext report
}

private const val TAG = "CloudPrewarm"
