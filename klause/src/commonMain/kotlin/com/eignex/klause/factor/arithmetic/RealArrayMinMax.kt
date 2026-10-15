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

/** Exact real minimum or maximum, enforced by a selected complete arithmetic consumer. */
class RealArrayMinMax(
    /** Result coordinate in the source real namespace. */
    val result: Int,
    /** Ordered operand coordinates in the source real namespace. */
    val xs: IntArray,
    /** Select the maximum when true and the minimum otherwise. */
    val max: Boolean,
) : Factor {
    init {
        require(xs.isNotEmpty()) { "RealArrayMinMax needs at least one operand" }
        require(result >= 0 && xs.all { it >= 0 })
    }

    override val variables: VarList = RealVars((intArrayOf(result) + xs).distinct().toIntArray())

    override fun remap(mapping: VarRemap): Factor =
        RealArrayMinMax(mapping.real(result), IntArray(xs.size) { mapping.real(xs[it]) }, max)

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.REAL_ARRAY_MIN_MAX, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int =
        hashRemappedKey(FactorKind.REAL_ARRAY_MIN_MAX, mapping, ::buildKey)

    override val structuralKeyWeight: Int get() = (xs.size.toLong() + 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun buildKey(sink: KeySink) {
        sink.realVar(result)
        sink.bool(max)
        sink.int(xs.size)
        for (operand in xs) sink.realVar(operand)
    }

    override val linearForm: LinearForm = LinearForm.Relaxation(xs.map { operand ->
        Linear(intArrayOf(), doubleArrayOf(), intArrayOf(result, operand), doubleArrayOf(1.0, -1.0),
            if (max) LinearOp.GE else LinearOp.LE, 0.0)
    })
}
