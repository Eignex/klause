package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.RealVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap

/**
 * Exact continuous equality `result = a * b`, with every ID in the source real namespace.
 * Aliasing in any role is valid: `a == b` expresses a square. Products can share results and
 * operands to form chains. Exact rational or algebraic theory witnesses enforce this equality;
 * finite propagation, floating scores and linear relaxations cannot certify it.
 */
class ContinuousProduct(
    /** First real operand ID. */
    val a: Int,
    /** Second real operand ID. */
    val b: Int,
    /** Real result ID. */
    val result: Int,
) : Factor {
    init {
        require(a >= 0 && b >= 0 && result >= 0) { "Continuous product IDs must be nonnegative" }
    }

    override val variables: VarList = RealVars(intArrayOf(a, b, result).distinct().toIntArray())

    override fun remap(mapping: VarRemap): Factor =
        ContinuousProduct(mapping.real(a), mapping.real(b), mapping.real(result))

    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.CONTINUOUS_PRODUCT) {
        int(minOf(a, b))
        int(maxOf(a, b))
        int(result)
    }

    override val structuralKeyWeight: Int get() = 3
}
