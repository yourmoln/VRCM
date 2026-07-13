package io.github.vrcmteam.vrcm.core.algorithms

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class ForceLayoutResult(
    val positions: Map<String, Offset>,
    val layoutWidthPx: Float,
    val layoutHeightPx: Float,
)

private const val FORCE_ITERATIONS = 500
private const val CONVERGENCE_THRESHOLD = 0.5f
private const val MIN_ITERATIONS = 30

/**
 * 力导向布局算法（简化版 ForceAtlas2）
 * 含提前收敛退出
 *
 * @param nodeIds 节点 ID 列表
 * @param edges 邻接表，key 为节点 ID，value 为邻居节点 ID 列表
 * @param desiredSpacing 节点间期望间距（像素）
 * @return 布局结果，包含每个节点的位置和画布尺寸
 */
fun computeForceLayout(
    nodeIds: List<String>,
    edges: Map<String, List<String>>,
    desiredSpacing: Float,
): ForceLayoutResult {
    val n = nodeIds.size
    if (n == 0) return ForceLayoutResult(emptyMap(), 0f, 0f)

    val idx = nodeIds.mapIndexed { i, id -> id to i }.toMap()

    // 计算画布尺寸
    val maxRadius = desiredSpacing * sqrt(n.coerceAtLeast(1).toFloat()) * 2f
    val requiredDiameter = maxRadius * 2f + desiredSpacing
    val scaleFactor = maxOf(1f, sqrt(n.toFloat().coerceAtLeast(1f)) / 10f)
    val referenceSize = 1000f * scaleFactor
    val layoutWidthPx = maxOf(referenceSize, requiredDiameter)
    val layoutHeightPx = maxOf(referenceSize, requiredDiameter)
    val center = Offset(layoutWidthPx / 2f, layoutHeightPx / 2f)

    // 初始化位置：随机散布在中心附近
    val x = FloatArray(n)
    val y = FloatArray(n)
    val rng = kotlin.random.Random(42)
    for (i in 0 until n) {
        val angle = rng.nextFloat() * 2f * PI.toFloat()
        val r = rng.nextFloat() * desiredSpacing * 0.3f
        x[i] = center.x + r * cos(angle)
        y[i] = center.y + r * sin(angle)
    }

    // 力导向迭代
    val k = desiredSpacing * 1.5f
    val gravity = 1.6f
    val speed = 1f
    val fx = FloatArray(n)
    val fy = FloatArray(n)

    for (iter in 0 until FORCE_ITERATIONS) {
        fx.fill(0f)
        fy.fill(0f)

        // 斥力：所有节点对之间
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                var dx = x[i] - x[j]
                var dy = y[i] - y[j]
                val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                val force = (k * k) / dist
                val fxij = (dx / dist) * force
                val fyij = (dy / dist) * force
                fx[i] += fxij; fy[i] += fyij
                fx[j] -= fxij; fy[j] -= fyij
            }
        }

        // 引力：连接的节点之间
        for (i in 0 until n) {
            val nodeId = nodeIds[i]
            val neighbors = edges[nodeId].orEmpty()
            for (neighborId in neighbors) {
                val j = idx[neighborId] ?: continue
                if (i >= j) continue
                var dx = x[j] - x[i]
                var dy = y[j] - y[i]
                val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                val force = (dist * dist) / k
                val fxij = (dx / dist) * force
                val fyij = (dy / dist) * force
                fx[i] += fxij; fy[i] += fyij
                fx[j] -= fxij; fy[j] -= fyij
            }
        }

        // 重力：向中心吸引
        for (i in 0 until n) {
            val dx = center.x - x[i]
            val dy = center.y - y[i]
            fx[i] += dx * gravity
            fy[i] += dy * gravity
        }

        // 更新位置（带速度限制）
        val maxDisplacement = k * speed * (1f - iter.toFloat() / FORCE_ITERATIONS)
        var maxMove = 0f
        for (i in 0 until n) {
            val fMag = sqrt(fx[i] * fx[i] + fy[i] * fy[i]).coerceAtLeast(0.01f)
            val disp = min(fMag, maxDisplacement)
            val moveX = (fx[i] / fMag) * disp
            val moveY = (fy[i] / fMag) * disp
            x[i] += moveX
            y[i] += moveY
            val moveMag = sqrt(moveX * moveX + moveY * moveY)
            if (moveMag > maxMove) maxMove = moveMag
        }

        // 提前收敛检查：跳过前几轮不稳定振荡
        if (iter >= MIN_ITERATIONS && maxMove < CONVERGENCE_THRESHOLD) break
    }

    val positions = nodeIds.mapIndexed { i, id -> id to Offset(x[i], y[i]) }.toMap()
    return ForceLayoutResult(positions, layoutWidthPx, layoutHeightPx)
}
