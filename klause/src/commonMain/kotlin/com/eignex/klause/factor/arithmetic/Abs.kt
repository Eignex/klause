package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.IntVars
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.hashRemappedKey
import com.eignex.klause.ir.materializeKey

/** Exact integer `result = |operand|`, without signed-word wraparound. */
class Abs(
    /** Integer operand coordinate. */
    val operand: Int,
    /** Integer result coordinate. */
    val result: Int,
) : Factor {
    init {
        require(operand >= 0 && result >= 0) { "Absolute-value IDs must be nonnegative" }
    }

    override val variables: VarList = IntVars(intArrayOf(operand, result).distinct().toIntArray())

    override fun remap(mapping: VarRemap): Factor = Abs(mapping.int(operand), mapping.int(result))

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.ABS, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int = hashRemappedKey(FactorKind.ABS, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.intVar(operand)
        sink.intVar(result)
    }

    override val linearForm: LinearForm = LinearForm.Relaxation(
        listOf(
            Linear(intArrayOf(1, -1), intArrayOf(operand, result), LinearOp.LE, 0),
            Linear(intArrayOf(-1, -1), intArrayOf(operand, result), LinearOp.LE, 0),
        ),
    )
}
