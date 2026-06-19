package com.eignex.klause.solver.factor

import com.eignex.klause.util.IntIntMap

/**
 * O(1) coefficient lookup keyed by variable id, replacing the naïve linear scan in
 * `Linear.coeffOf` / `ReifiedLinear.coeffOf`. Backed by [IntIntMap], which transparently picks a
 * dense offset-[IntArray] when the var-id range is reasonably dense and a sparse open-addressing
 * primitive hash table otherwise — neither backing boxes keys or values. Variables not in the
 * original term list resolve to coefficient 0 (caller code may legitimately ask about an
 * unrelated var). Callers coalesce duplicate vars upstream, so keys are distinct here.
 */
internal class CoeffLookup private constructor(private val map: IntIntMap) {
    fun coeffOf(intVar: Int): Int = map[intVar]

    companion object {
        fun build(vars: IntArray, coeffs: IntArray): CoeffLookup {
            require(vars.size == coeffs.size) { "vars/coeffs length mismatch" }
            return CoeffLookup(IntIntMap.build(vars, coeffs, absent = 0))
        }
    }
}
