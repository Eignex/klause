package com.eignex.klause.factor.bool

import com.eignex.klause.factor.litVars
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.BoolVars
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.KeySink
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.VarList
import com.eignex.klause.solver.VarRemap
import com.eignex.klause.solver.hashRemappedKey
import com.eignex.klause.solver.materializeKey

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

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.XOR, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int = hashRemappedKey(FactorKind.XOR, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.int(targetParity)
        sink.sortedBoolLits(literals)
    }

    override fun remap(mapping: VarRemap): Factor = Xor(mapping.lits(literals), targetParity)

    override val variables: VarList = BoolVars(literals.litVars())

    override fun asPropagator(): Propagator = XorPropagator(boolVars, intVars, literals, targetParity)

    override fun asInvariant(): Invariant = XorInvariant(boolVars, literals, targetParity)
}
