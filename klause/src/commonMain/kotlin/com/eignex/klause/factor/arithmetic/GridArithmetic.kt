package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.SpanIntVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.materializeKey
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

internal data class GridCoordinate(val variable: Int, val lower: Double, val upper: Double, val buckets: Int) {
    init {
        require(variable >= 0 && lower.isFinite() && upper.isFinite() && lower <= upper && buckets > 0)
        require((upper - lower).isFinite())
    }

    private val step = if (buckets > 1) (upper - lower) / (buckets - 1) else 0.0

    fun value(index: Long): Double = lower + index * step

    fun index(value: Double): Long? {
        if (!value.isFinite() || value < lower - step * ROUNDING_TOLERANCE ||
            value > upper + step * ROUNDING_TOLERANCE) return null
        if (step == 0.0) return 0L.takeIf { value == lower }
        val position = (value - lower) / step
        val rounded = round(position)
        if (!rounded.isFinite() || abs(position - rounded) > ROUNDING_TOLERANCE ||
            rounded < 0.0 || rounded >= buckets) return null
        return rounded.toLong()
    }

    fun contains(index: Long): Boolean = index >= 0L && index < buckets

    fun remap(mapping: VarRemap): GridCoordinate = copy(variable = mapping.int(variable))

    fun key(sink: KeySink) {
        sink.intVar(variable)
        sink.long(lower.toRawBits())
        sink.long(upper.toRawBits())
        sink.int(buckets)
    }

    private companion object {
        const val ROUNDING_TOLERANCE = 0.5
    }
}

internal sealed interface GridArithmetic : Factor {
    val inputs: List<GridCoordinate>
    val output: GridCoordinate
    fun evaluate(value: (Int) -> Long): Double
    fun image(value: (Int) -> Long): Long? = output.index(evaluate(value))

    fun accepts(value: (Int) -> Long): Boolean =
        inputs.all { it.contains(value(it.variable)) } && output.contains(value(output.variable)) &&
            image(value) == value(output.variable)
}

internal class GridProduct(val a: GridCoordinate, val b: GridCoordinate, override val output: GridCoordinate) :
    GridArithmetic {
    override val inputs: List<GridCoordinate> = listOf(a, b)
    override val variables: VarList =
        SpanIntVars(intArrayOf(a.variable, b.variable, output.variable).distinct().toIntArray())

    override fun evaluate(value: (Int) -> Long): Double = a.value(value(a.variable)) * b.value(value(b.variable))

    override fun remap(mapping: VarRemap): Factor =
        GridProduct(a.remap(mapping), b.remap(mapping), output.remap(mapping))

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.GRID_PRODUCT) { sink ->
        val ordered = if (compareValuesBy(a, b, { it.variable }, { it.lower.toRawBits() },
                { it.upper.toRawBits() }, { it.buckets }) <= 0) listOf(a, b) else listOf(b, a)
        ordered.forEach { it.key(sink) }
        output.key(sink)
    }

    override val structuralKeyWeight: Int get() = 12
}

internal class GridPower(val base: GridCoordinate, val exponent: Int, override val output: GridCoordinate) :
    GridArithmetic {
    init { require(exponent >= 0) }

    override val inputs: List<GridCoordinate> = listOf(base)
    override val variables: VarList = SpanIntVars(intArrayOf(base.variable, output.variable).distinct().toIntArray())

    override fun evaluate(value: (Int) -> Long): Double = base.value(value(base.variable)).pow(exponent)

    override fun remap(mapping: VarRemap): Factor = GridPower(base.remap(mapping), exponent, output.remap(mapping))

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.GRID_POWER) { sink ->
        base.key(sink)
        sink.int(exponent)
        output.key(sink)
    }

    override val structuralKeyWeight: Int get() = 9
}
