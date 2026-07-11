package com.fankes.coloros.notify.hook.icon

import kotlin.math.roundToInt

internal data class RenderSize(
    val width: Int,
    val height: Int,
)

internal fun fitWithinRenderBounds(
    intrinsicWidth: Int,
    intrinsicHeight: Int,
    maxDimension: Int,
): RenderSize {
    require(maxDimension > 0) { "maxDimension must be positive" }
    val width = intrinsicWidth.takeIf { it > 0 } ?: maxDimension
    val height = intrinsicHeight.takeIf { it > 0 } ?: maxDimension
    val scale = minOf(
        1.0,
        maxDimension.toDouble() / width.toDouble(),
        maxDimension.toDouble() / height.toDouble(),
    )
    return RenderSize(
        width = (width * scale).roundToInt().coerceIn(1, maxDimension),
        height = (height * scale).roundToInt().coerceIn(1, maxDimension),
    )
}

internal fun cappedSquareRenderSize(
    targetSize: Int,
    intrinsicWidth: Int,
    intrinsicHeight: Int,
    maxDimension: Int,
): Int {
    require(maxDimension > 0) { "maxDimension must be positive" }
    return maxOf(
        targetSize.coerceAtLeast(1),
        intrinsicWidth.coerceAtLeast(0),
        intrinsicHeight.coerceAtLeast(0),
    ).coerceAtMost(maxDimension)
}
