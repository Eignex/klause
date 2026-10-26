package com.eignex.klause.lp.cut

internal fun <T> selectNonparallelCuts(
    candidates: List<T>,
    maximum: Int,
    maximumCosine: Double,
    forced: List<T> = emptyList(),
    cosine: (T, T) -> Double,
): List<T> {
    if (maximum <= 0) return emptyList()
    val selected = ArrayList<T>()
    for (candidate in candidates) {
        if (selected.size >= maximum) break
        if (forced.none { cosine(it, candidate) > maximumCosine } &&
            selected.none { cosine(it, candidate) > maximumCosine }) selected += candidate
    }
    return selected
}
