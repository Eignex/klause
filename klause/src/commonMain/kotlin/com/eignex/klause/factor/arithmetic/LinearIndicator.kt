package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.MixedVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarRemap

/** An exact linear source row enforced when an integer indicator equals its declared zero/one trigger. */
class LinearIndicator(
    /** Integer source coordinate controlling the implication. */
    val indicator: Int,
    /** Whether one, rather than zero, enables [row]. */
    val whenOne: Boolean,
    /** Exact enabled source comparison. */
    val row: ExactLinear,
) : Factor {
    init {
        require(indicator >= 0)
        require(!row.strict) { "indicator row must be non-strict" }
        require(row.activator == LinearRow.ALWAYS) { "indicator row must be unconditional" }
    }

    override val variables: MixedVars = MixedVars(
        boundInts = (row.intVars.toList() + indicator).distinct().toIntArray(),
        reals = row.variables.reals,
    )

    override fun remap(mapping: VarRemap): Factor = LinearIndicator(mapping.int(indicator), whenOne, row.remap(mapping))

    override fun structuralKey(): StructuralKey = key()

    override fun remapStructuralHash(mapping: VarRemap): Int = key(mapping).hashCode()

    private fun key(mapping: VarRemap? = null): StructuralKey = StructuralKey.of(FactorKind.LINEAR_INDICATOR) {
        int(mapping?.int(indicator) ?: indicator)
        bool(whenOne)
        ints(row.key(mapping).toString().map { it.code }.toIntArray())
    }
}
