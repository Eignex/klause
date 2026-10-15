package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.SpanIntVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.hashRemappedKey
import com.eignex.klause.ir.materializeKey
import com.eignex.klause.util.subExact
import kotlin.math.roundToLong

internal class GridArrayMinMax(
    val coordinates: IntArray,
    val steps: DoubleArray,
    val origins: DoubleArray,
    val constants: BooleanArray,
    val scale: Long,
    val max: Boolean,
) : Factor {
    init {
        require(coordinates.size >= 2 && steps.size == coordinates.size && origins.size == coordinates.size &&
            constants.size == coordinates.size)
        require(coordinates.all { it >= 0 } && steps.all { it.isFinite() && it >= 0.0 })
        require(origins.all { it.isFinite() } && scale > 0)
    }

    override val variables: VarList = SpanIntVars(coordinates.distinct().toIntArray())

    override fun remap(mapping: VarRemap): Factor =
        GridArrayMinMax(mapping.ints(coordinates), steps, origins, constants, scale, max)

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.GRID_ARRAY_MIN_MAX, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int =
        hashRemappedKey(FactorKind.GRID_ARRAY_MIN_MAX, mapping, ::buildKey)

    override val structuralKeyWeight: Int get() =
        (coordinates.size.toLong() * 4 + 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun buildKey(sink: KeySink) {
        sink.bool(max)
        sink.intVars(coordinates)
        sink.long(scale)
        for (position in coordinates.indices) {
            sink.bool(constants[position])
            sink.long(steps[position].toRawBits())
            sink.long(origins[position].toRawBits())
        }
    }

    fun comparison(position: Int, equality: Boolean): Linear {
        val left = if (max || equality) position else 0
        val right = if (max || equality) 0 else position
        val operation = if (equality) LinearOp.EQ else LinearOp.LE
        if (constants[left] && constants[right]) {
            val holds = if (equality) origins[left] == origins[right] else origins[left] <= origins[right]
            return Linear(longArrayOf(0), intArrayOf(coordinates[0]), operation, if (holds) 0L else if (equality) 1L else -1L)
        }
        if (constants[left]) {
            return Linear(longArrayOf(scaled(-steps[right])), intArrayOf(coordinates[right]), operation,
                subExact(scaled(-origins[left]), scaled(-origins[right])))
        }
        if (constants[right]) {
            return Linear(longArrayOf(scaled(steps[left])), intArrayOf(coordinates[left]), operation,
                subExact(scaled(origins[right]), scaled(origins[left])))
        }
        subExact(0L, scaled(origins[left]))
        return Linear(longArrayOf(scaled(steps[left]), scaled(-steps[right])),
            intArrayOf(coordinates[left], coordinates[right]), operation,
            subExact(scaled(origins[right]), scaled(origins[left])))
    }

    private fun scaled(value: Double): Long {
        val converted = value * scale
        require(converted.isFinite() && converted >= Long.MIN_VALUE.toDouble() && converted < Long.MAX_VALUE.toDouble())
        return converted.roundToLong()
    }

    override val linearForm: LinearForm get() = LinearForm.Relaxation(
        (1 until coordinates.size).map { comparison(it, equality = false) },
    )
}
