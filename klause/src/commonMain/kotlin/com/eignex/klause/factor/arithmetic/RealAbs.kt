package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.RealVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.hashRemappedKey
import com.eignex.klause.ir.materializeKey

/** Exact real `result = |operand|`, enforced by the selected arithmetic consumer. */
class RealAbs(
    /** Operand coordinate in the source real namespace. */
    val operand: Int,
    /** Result coordinate in the source real namespace. */
    val result: Int,
) : Factor {
    init {
        require(operand >= 0 && result >= 0) { "Absolute-value IDs must be nonnegative" }
    }

    override val variables: VarList = RealVars(intArrayOf(operand, result).distinct().toIntArray())

    override fun remap(mapping: VarRemap): Factor = RealAbs(mapping.real(operand), mapping.real(result))

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.REAL_ABS, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int =
        hashRemappedKey(FactorKind.REAL_ABS, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.realVar(operand)
        sink.realVar(result)
    }

    override val structuralKeyWeight: Int get() = 2

    override val linearForm: LinearForm = LinearForm.Relaxation(
        listOf(
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(operand, result),
                doubleArrayOf(1.0, -1.0), LinearOp.LE, 0.0),
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(operand, result),
                doubleArrayOf(-1.0, -1.0), LinearOp.LE, 0.0),
        ),
    )
}
