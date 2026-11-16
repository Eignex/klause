package com.eignex.klause.bound

// Camerini-Fratta-Maffioli deflection keeps the direction aligned with the subgradient;
// the Polyak step changes bound tightness, while exact subproblem evaluation supplies authority.
internal fun LongArray.deflectedSubgradientStep(
    gradient: LongArray,
    numerator: Long,
    incumbent: Double,
    previousDirection: DoubleArray,
    denominator: Long,
    sign: IntArray,
): Boolean {
    var gNorm2 = 0.0
    for (value in gradient) gNorm2 += value.toDouble() * value.toDouble()
    if (gNorm2 == 0.0) return false
    var gDotPrev = 0.0
    var prevNorm2 = 0.0
    for (r in gradient.indices) {
        gDotPrev += gradient[r].toDouble() * previousDirection[r]
        prevNorm2 += previousDirection[r] * previousDirection[r]
    }
    val beta = if (prevNorm2 > 0.0 && gDotPrev < 0.0) -gDotPrev / prevNorm2 else 0.0
    var dNorm2 = 0.0
    val d = DoubleArray(gradient.size)
    for (r in gradient.indices) {
        d[r] = gradient[r].toDouble() + beta * previousDirection[r]
        dNorm2 += d[r] * d[r]
        previousDirection[r] = d[r]
    }
    if (dNorm2 == 0.0) return false
    // Polyak along the deflected direction: t = (UB − L) / ‖d‖²; p_r += trunc(Q·t·d_r), projected.
    val lValue = numerator.toDouble() / denominator.toDouble()
    val t = (incumbent - lValue) / dNorm2
    for (r in gradient.indices) {
        val step = (denominator.toDouble() * t * d[r]).toLong()
        var pr = this[r] + step
        when (sign[r]) {
            1 -> if (pr < 0L) pr = 0L
            -1 -> if (pr > 0L) pr = 0L
        }
        this[r] = pr
    }
    return true
}
