package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.SpanIntVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.hashRemappedKey
import com.eignex.klause.ir.materializeKey
import kotlin.math.abs
import kotlin.math.round

internal class GridAbs(
    val operand: Int,
    val result: Int,
    val operandLower: Double,
    val operandUpper: Double,
    val operandBuckets: Int,
    val resultLower: Double,
    val resultUpper: Double,
    val resultBuckets: Int,
    capturedImage: IntArray? = null,
) : Factor {
    init {
        require(operand >= 0 && result >= 0 && operandBuckets > 0 && resultBuckets > 0)
    }

    val image: IntArray = capturedImage ?: buildImage()
    val hasImage: Boolean get() = image.any { it >= 0 }

    override val variables: VarList = SpanIntVars(intArrayOf(operand, result).distinct().toIntArray())

    override fun remap(mapping: VarRemap): Factor = GridAbs(
        mapping.int(operand), mapping.int(result), operandLower, operandUpper, operandBuckets,
        resultLower, resultUpper, resultBuckets, image,
    )

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.GRID_ABS, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int =
        hashRemappedKey(FactorKind.GRID_ABS, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.intVar(operand)
        sink.intVar(result)
        sink.long(operandLower.toRawBits())
        sink.long(operandUpper.toRawBits())
        sink.int(operandBuckets)
        sink.long(resultLower.toRawBits())
        sink.long(resultUpper.toRawBits())
        sink.int(resultBuckets)
    }

    override val structuralKeyWeight: Int get() = 8

    private fun buildImage(): IntArray {
        val inputStep = if (operandBuckets > 1) (operandUpper - operandLower) / (operandBuckets - 1) else 0.0
        val outputStep = if (resultBuckets > 1) (resultUpper - resultLower) / (resultBuckets - 1) else 0.0
        return IntArray(operandBuckets) { index ->
            val magnitude = abs(operandLower + index * inputStep)
            if (magnitude < resultLower - outputStep * ROUNDING_TOLERANCE ||
                magnitude > resultUpper + outputStep * ROUNDING_TOLERANCE
            ) return@IntArray -1
            val output = if (outputStep == 0.0) 0 else {
                val position = (magnitude - resultLower) / outputStep
                val rounded = round(position).toInt()
                if (abs(position - rounded) > ROUNDING_TOLERANCE) -1 else rounded
            }
            if (output in 0 until resultBuckets) output else -1
        }
    }

    private companion object {
        const val ROUNDING_TOLERANCE = 0.5
    }
}
