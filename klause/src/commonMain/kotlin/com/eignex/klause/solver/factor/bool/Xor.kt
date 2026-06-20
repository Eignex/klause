package com.eignex.klause.solver.factor.bool

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.factor.CoeffLookup
import com.eignex.klause.solver.factor.litVars
import com.eignex.klause.solver.factor.remapLits

/**
 * `XOR(lit_1, ..., lit_n) == targetParity`. `targetParity = 1` means an odd number of literals
 * must be true; `targetParity = 0` means even. Payload at `intPayload[factorId]` is the current
 * parity (0 or 1). Each Boolean flip toggles the parity exactly once per occurrence of that var
 * in the literal list.
 */
class Xor(
    /** The literals whose parity is constrained. */
    override val literals: IntArray,
    /** Required parity (0 = even number of true literals, 1 = odd). */
    override val targetParity: Int,
) : Factor,
    XorPropagator,
    XorInvariant {

    init {
        require(literals.isNotEmpty()) { "Xor needs at least one literal" }
        require(targetParity == 0 || targetParity == 1) { "targetParity must be 0 or 1" }
    }

    override fun structuralKey(): String = "xor:$targetParity:" + literals.sorted().joinToString(",")

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = Xor(literals.remapLits(boolMap), targetParity)

    override val boolVars: IntArray = literals.litVars()
    override val intVars: IntArray = EmptyIntArray

    /** Per-var parity contribution: precomputed `(occurrences in `literals`) and 1` per `boolVar`.
     *  Flipping a var toggles factor parity by exactly this amount. */
    private val parityByVar: CoeffLookup = run {
        val parities = IntArray(boolVars.size)
        for (i in boolVars.indices) {
            var n = 0
            for (lit in literals) if (Lit.variable(lit) == boolVars[i]) n++
            parities[i] = n and 1
        }
        CoeffLookup.build(boolVars, parities)
    }

    override fun parityOf(v: Int): Int = parityByVar.coeffOf(v)
}
