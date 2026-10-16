package com.eignex.klause.factor.table

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.MixedVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.materializeKey

/** Equality of [result] to the real arm selected by a signed Boolean [condition]. */
class RealChoice(
    /** Signed Boolean literal selecting [whenTrue] when satisfied. */
    val condition: Int,
    /** Real result coordinate. */
    val result: Int,
    /** Real coordinate selected when [condition] holds. */
    val whenTrue: Int,
    /** Real coordinate selected when [condition] does not hold. */
    val whenFalse: Int,
) : Factor {
    init {
        require(condition >= 0 && result >= 0 && whenTrue >= 0 && whenFalse >= 0)
    }

    override val variables: VarList = MixedVars(boolVars = intArrayOf(Lit.variable(condition)),
        reals = intArrayOf(result, whenTrue, whenFalse).distinct().toIntArray())

    override val structuralKeyWeight: Int get() = 4

    override fun remap(mapping: VarRemap): Factor = RealChoice(mapping.lit(condition), mapping.real(result),
        mapping.real(whenTrue), mapping.real(whenFalse))

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.REAL_CHOICE, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.boolLit(condition)
        sink.realVar(result)
        sink.realVar(whenTrue)
        sink.realVar(whenFalse)
    }
}
