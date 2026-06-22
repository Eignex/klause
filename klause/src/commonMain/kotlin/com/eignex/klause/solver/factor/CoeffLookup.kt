package com.eignex.klause.solver.factor

import com.eignex.klause.util.IntIntMap

// O(1) coefficient lookup; absent = 0 so callers may ask about any var.
// Callers coalesce duplicate vars upstream (keys are distinct).
internal class CoeffLookup private constructor(private val map: IntIntMap) {
    fun coeffOf(intVar: Int): Int = map[intVar]

    companion object {
        fun build(vars: IntArray, coeffs: IntArray): CoeffLookup {
            require(vars.size == coeffs.size) { "vars/coeffs length mismatch" }
            return CoeffLookup(IntIntMap.build(vars, coeffs, absent = 0))
        }
    }
}
