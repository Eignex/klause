package com.eignex.klause.solver.factor

/**
 * O(1) coefficient lookup keyed by variable id, replacing the naïve linear scan in
 * [Linear.coeffOf] / [ReifiedLinear.coeffOf]. Picks an [IntArray] indexed by `varId - min`
 * when the var-id range is reasonably dense, falling back to a [HashMap] when the range is
 * too sparse to justify the array allocation. Variables not in the original term list resolve
 * to coefficient 0 (caller code may legitimately ask about an unrelated var).
 */
internal sealed interface CoeffLookup {
    fun coeffOf(intVar: Int): Int

    companion object {
        fun build(vars: IntArray, coeffs: IntArray): CoeffLookup {
            require(vars.size == coeffs.size) { "vars/coeffs length mismatch" }
            if (vars.isEmpty()) return MapLookup(HashMap())
            var lo = vars[0]
            var hi = vars[0]
            for (v in vars) {
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            val range = (hi - lo).toLong() + 1
            return if (range <= 4L * vars.size) {
                val arr = IntArray(range.toInt())
                for (i in vars.indices) arr[vars[i] - lo] = coeffs[i]
                ArrayLookup(lo, arr)
            } else {
                val m = HashMap<Int, Int>(vars.size)
                for (i in vars.indices) m[vars[i]] = coeffs[i]
                MapLookup(m)
            }
        }
    }
}

private class ArrayLookup(val minVar: Int, val coeffs: IntArray) : CoeffLookup {
    override fun coeffOf(intVar: Int): Int {
        val idx = intVar - minVar
        return if (idx in coeffs.indices) coeffs[idx] else 0
    }
}

private class MapLookup(val map: HashMap<Int, Int>) : CoeffLookup {
    override fun coeffOf(intVar: Int): Int = map[intVar] ?: 0
}
