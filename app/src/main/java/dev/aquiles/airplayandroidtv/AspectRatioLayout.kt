package dev.aquiles.airplayandroidtv

object AspectRatioLayout {

    fun computeFitCenter(
        containerWidth: Int,
        containerHeight: Int,
        contentWidth: Int,
        contentHeight: Int
    ): Pair<Int, Int> {
        if (containerWidth <= 0 || containerHeight <= 0) return 0 to 0
        if (contentWidth <= 0 || contentHeight <= 0) return containerWidth to containerHeight

        val contentAspect = contentWidth.toFloat() / contentHeight
        val containerAspect = containerWidth.toFloat() / containerHeight

        return if (contentAspect > containerAspect) {
            containerWidth to (containerWidth / contentAspect).toInt().coerceAtLeast(1)
        } else {
            (containerHeight * contentAspect).toInt().coerceAtLeast(1) to containerHeight
        }
    }
}
