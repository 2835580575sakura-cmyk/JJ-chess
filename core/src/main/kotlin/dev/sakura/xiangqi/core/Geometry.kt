package dev.sakura.xiangqi.core
import kotlinx.serialization.Serializable
@Serializable data class FloatPoint(val x: Float, val y: Float)
@Serializable data class FloatRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun valid() = listOf(left, top, right, bottom).all { it.isFinite() } && left >= 0 && top >= 0 && width > 0 && height > 0
}
@Serializable enum class Orientation { RED_BOTTOM, BLACK_BOTTOM }
enum class CoordinateSpace { CAPTURE, DISPLAY }
@Serializable data class GridCalibration(val captureRect: FloatRect, val displayRect: FloatRect, val orientation: Orientation = Orientation.RED_BOTTOM, val transformRevision: Long = 0) {
    fun isValid() = captureRect.valid() && displayRect.valid()
    fun center(square: Square, space: CoordinateSpace): FloatPoint {
        require(isValid())
        val r = if (space == CoordinateSpace.CAPTURE) captureRect else displayRect
        val file = if (orientation == Orientation.RED_BOTTOM) square.file else 8 - square.file
        val row = if (orientation == Orientation.RED_BOTTOM) 9 - square.rank else square.rank
        return FloatPoint(r.left + r.width * file / 8, r.top + r.height * row / 9)
    }
}
