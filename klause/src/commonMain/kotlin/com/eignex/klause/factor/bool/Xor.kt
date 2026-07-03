package com.eignex.klause.factor.bool

import com.eignex.klause.factor.litVars
import com.eignex.klause.factor.remapLits
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.util.EmptyIntArray

/**
 * `XOR(lit_1, ..., lit_n) == targetParity`. `targetParity = 1` means an odd number of literals
 * must be true; `targetParity = 0` means even. Payload at `intPayload(factorId)` is the current
 * parity (0 or 1). Each Boolean flip toggles the parity exactly once per occurrence of that var
 * in the literal list.
 */
class Xor(
    /** The literals whose parity is constrained. */
    val literals: IntArray,
    /** Required parity (0 = even number of true literals, 1 = odd). */
    val targetParity: Int,
) : Factor {

    init {
        require(literals.isNotEmpty()) { "Xor needs at least one literal" }
        require(targetParity == 0 || targetParity == 1) { "targetParity must be 0 or 1" }
    }

    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.XOR) {
        int(targetParity)
        sortedInts(literals)
    }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = Xor(literals.remapLits(boolMap), targetParity)

    override val boolVars: IntArray = literals.litVars()
    override val intVars: IntArray = EmptyIntArray

    override fun asPropagator(): Propagator = XorPropagator(boolVars, intVars, literals, targetParity)

    override fun asInvariant(): Invariant = XorInvariant(boolVars, literals, targetParity)
}
