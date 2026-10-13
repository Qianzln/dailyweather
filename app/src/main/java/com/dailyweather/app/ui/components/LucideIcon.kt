package com.dailyweather.app.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import java.io.InputStream

/**
 * 自研 SVG → ImageVector 引擎（不引入三方图片库）。
 *
 * 同时吃南风在用的两套图标：
 * - `lucide/`：`viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"` 的线框图标（MIT）；
 * - `sf/`：SF Symbols 填充图标，viewBox 带**负原点**且非正方（如 `255 -1651 2366 1855`）。
 *
 * ImageVector 的视口没有原点偏移，所以这里把路径节点整体平移掉 viewBox 的最小值，
 * 并把视口补成正方、图形居中——调用方一律 `Modifier.size(n)` 就能拿到等高的图标。
 */
object IconRegistry {

    private val cache = HashMap<String, ImageVector?>()

    fun load(context: Context, dir: String, name: String): ImageVector? {
        val key = "$dir/$name"
        synchronized(cache) { cache[key] }?.let { return it }
        val parsed = runCatching { context.assets.open("$key.svg").use(::parse) }.getOrNull()
        synchronized(cache) { cache[key] = parsed }
        return parsed
    }

    private fun parse(stream: InputStream): ImageVector? {
        val xml = android.util.Xml.newPullParser().also { it.setInput(stream, null) }
        val paths = mutableListOf<List<PathNode>>()
        // 每个子元素**自己**声明的 stroke-width / stroke-linecap。
        // ⚠️ SVG 允许把描边属性写在 `<path>` 上而不是 `<svg>` 上——只读 svg 层会漏：
        //   `assets/lucide/plus.svg` 的 `<svg>` 里没有 stroke-width，`<path stroke-width="2.2">` 才有，
        //   于是 strokeWidth=0 → 走「填充」分支 → 一条只有描边没有面积的路径**什么都不画**，
        //   顶栏「添加城市」按钮变成一个**空玻璃圆**（用户反馈「这个部分我不知道是什么、冗余」）。
        //   全库 54 个图标里 39 个写在 `<svg>`、13 个纯填充、2 个（plus / check-filled）写在子元素上。
        val pathStroke = mutableListOf<Pair<Float, Boolean>>()
        var viewBox: List<Float> = emptyList()
        var strokeWidth = 0f
        var roundCap = false

        fun attr(n: String) = xml.getAttributeValue(null, n)

        /** 记录一个图元：路径节点 + 它自己的描边属性（0 表示继承 `<svg>` 层）。 */
        fun addElement(nodes: List<PathNode>) {
            paths += nodes
            pathStroke += (attr("stroke-width")?.toFloatOrNull() ?: 0f) to (attr("stroke-linecap") == "round")
        }

        var event = xml.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                when (xml.name) {
                    "svg" -> {
                        viewBox = (attr("viewBox") ?: "").trim().split(' ', ',')
                            .mapNotNull { it.toFloatOrNull() }
                        strokeWidth = attr("stroke-width")?.toFloatOrNull() ?: 0f
                        roundCap = attr("stroke-linecap") == "round"
                    }
                    "path" -> attr("d")?.let { addElement(addPathNodes(it)) }
                    "circle" -> addElement(
                        circleNodes(
                            attr("cx")?.toFloatOrNull() ?: 0f,
                            attr("cy")?.toFloatOrNull() ?: 0f,
                            attr("r")?.toFloatOrNull() ?: 0f,
                        ),
                    )
                    "line" -> addElement(
                        listOf(
                            PathNode.MoveTo(attr("x1")?.toFloatOrNull() ?: 0f, attr("y1")?.toFloatOrNull() ?: 0f),
                            PathNode.LineTo(attr("x2")?.toFloatOrNull() ?: 0f, attr("y2")?.toFloatOrNull() ?: 0f),
                        ),
                    )
                    "polyline" -> addElement(polylineNodes(attr("points") ?: ""))
                    "polygon" -> addElement(polylineNodes(attr("points") ?: "") + PathNode.Close)
                    "rect" -> {
                        val x = attr("x")?.toFloatOrNull() ?: 0f
                        val y = attr("y")?.toFloatOrNull() ?: 0f
                        val w = attr("width")?.toFloatOrNull() ?: 0f
                        val h = attr("height")?.toFloatOrNull() ?: 0f
                        addElement(
                            listOf(
                                PathNode.MoveTo(x, y), PathNode.LineTo(x + w, y),
                                PathNode.LineTo(x + w, y + h), PathNode.LineTo(x, y + h), PathNode.Close,
                            ),
                        )
                    }
                }
            }
            event = xml.next()
        }
        if (paths.isEmpty()) return null

        val vb = if (viewBox.size == 4) viewBox else listOf(0f, 0f, 24f, 24f)
        val side = maxOf(vb[2], vb[3])
        if (side <= 0f) return null
        val dx = -(vb[0] - (side - vb[2]) / 2f)
        val dy = -(vb[1] - (side - vb[3]) / 2f)

        return ImageVector.Builder(
            name = "svg",
            defaultWidth = Dp(24f),
            defaultHeight = Dp(24f),
            viewportWidth = side,
            viewportHeight = side,
        ).apply {
            paths.forEachIndexed { i, nodes ->
                val shifted = if (dx == 0f && dy == 0f) nodes else nodes.map { it.moved(dx, dy) }
                // 元素级优先，缺省继承 `<svg>` 层（见 parse() 里 plus.svg 的坑）。
                val (own, ownRound) = pathStroke[i]
                val effWidth = if (own > 0f) own else strokeWidth
                val effRound = ownRound || roundCap
                if (effWidth > 0f) {
                    addPath(
                        pathData = shifted,
                        pathFillType = PathFillType.NonZero,
                        stroke = SolidColor(Color.Black),
                        fill = null,
                        strokeLineWidth = effWidth,
                        strokeLineCap = if (effRound) StrokeCap.Round else StrokeCap.Butt,
                        strokeLineJoin = if (effRound) StrokeJoin.Round else StrokeJoin.Miter,
                    )
                } else {
                    addPath(
                        pathData = shifted,
                        pathFillType = PathFillType.NonZero,
                        fill = SolidColor(Color.Black),
                    )
                }
            }
        }.build()
    }

    /** 平移一个路径节点。SF 与 lucide 只用 M/L/H/V/C/Q/Z，其余原样返回。 */
    private fun PathNode.moved(dx: Float, dy: Float): PathNode = when (this) {
        is PathNode.MoveTo -> PathNode.MoveTo(x + dx, y + dy)
        is PathNode.LineTo -> PathNode.LineTo(x + dx, y + dy)
        is PathNode.HorizontalTo -> PathNode.HorizontalTo(x + dx)
        is PathNode.VerticalTo -> PathNode.VerticalTo(y + dy)
        is PathNode.CurveTo -> PathNode.CurveTo(x1 + dx, y1 + dy, x2 + dx, y2 + dy, x3 + dx, y3 + dy)
        is PathNode.ReflectiveCurveTo -> PathNode.ReflectiveCurveTo(x1 + dx, y1 + dy, x2 + dx, y2 + dy)
        is PathNode.QuadTo -> PathNode.QuadTo(x1 + dx, y1 + dy, x2 + dx, y2 + dy)
        is PathNode.ReflectiveQuadTo -> PathNode.ReflectiveQuadTo(x + dx, y + dy)
        else -> this
    }

    private fun circleNodes(cx: Float, cy: Float, r: Float): List<PathNode> {
        // 4 段三次贝塞尔近似整圆
        val k = r * 0.5522847498f
        return listOf(
            PathNode.MoveTo(cx + r, cy),
            PathNode.CurveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r),
            PathNode.CurveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy),
            PathNode.CurveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r),
            PathNode.CurveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy),
            PathNode.Close,
        )
    }

    private fun polylineNodes(points: String): List<PathNode> {
        val nums = points.trim().split(' ', ',').mapNotNull { it.toFloatOrNull() }
        val nodes = mutableListOf<PathNode>()
        var first = true
        nums.chunked(2).forEach { xy ->
            if (xy.size == 2) {
                if (first) { nodes += PathNode.MoveTo(xy[0], xy[1]); first = false }
                else nodes += PathNode.LineTo(xy[0], xy[1])
            }
        }
        return nodes
    }
}

@Composable
fun rememberLucide(name: String): ImageVector? {
    val context = LocalContext.current
    return remember(name) { IconRegistry.load(context, "lucide", name) }
}

/** SF Symbols 填充图标（生活建议网格用这套）。 */
@Composable
fun rememberSf(name: String): ImageVector? {
    val context = LocalContext.current
    return remember(name) { IconRegistry.load(context, "sf", "$name.fill") }
}
