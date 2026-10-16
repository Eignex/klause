package com.eignex.klause.solver.projection

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.arithmetic.RealAbs
import com.eignex.klause.factor.arithmetic.RealArrayMinMax
import com.eignex.klause.factor.table.RealElement
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.FloatInterval
import kotlin.math.abs
import kotlin.math.pow

private class FloatWindowDefinition(val output: Int, val inputs: IntArray, val relation: Factor)

internal fun prepareFloatGridColumns(
    source: Problem,
    requested: List<FloatGridVariable>,
    policy: FloatSemantics.Grid,
    cancellation: Cancellation,
): List<FloatGridColumn>? {
    val positions = requested.withIndex().associate { it.value.source.index to it.index }
    val auxiliary = requested.filter { it.auxiliary }.mapTo(HashSet()) { it.source.index }
    val columns = HashMap<Int, FloatGridColumn>()
    for ((index, variable) in requested.withIndex()) {
        cancellation.charge(1)
        if (cancellation()) return null
        val id = variable.source.index
        if (!variable.auxiliary || (source.realLower[id].isFinite() && source.realUpper[id].isFinite())) {
            columns[id] = floatGridColumn(source, variable, source.numIntVars + index, policy) ?: return null
        }
    }
    val definitions = ArrayList<FloatWindowDefinition>()
    for (factor in source.factors) {
        cancellation.charge(1)
        if (cancellation()) return null
        val definition = when (factor) {
            is ContinuousProduct -> FloatWindowDefinition(factor.result, intArrayOf(factor.a, factor.b), factor)
            is Power -> if (factor.base is Power.Coordinate.Real) {
                FloatWindowDefinition((factor.result as Power.Coordinate.Real).id.index,
                    intArrayOf(factor.base.id.index), factor)
            } else null
            is RealAbs -> FloatWindowDefinition(factor.result, intArrayOf(factor.operand), factor)
            is RealArrayMinMax -> FloatWindowDefinition(factor.result, factor.xs, factor)
            is RealElement -> FloatWindowDefinition(factor.result, factor.entries, factor)
            is Linear -> if (factor.op == LinearOp.EQ && factor.realConstants != null) {
                val outputs = factor.realVars.filter { it in auxiliary }.distinct()
                outputs.singleOrNull()?.let { output ->
                    FloatWindowDefinition(output, factor.realVars.filter { it != output }.toIntArray(), factor)
                }
            } else null
            else -> null
        }
        if (definition != null && definition.output in auxiliary) definitions += definition
    }
    val waiting = HashMap<Int, MutableList<Int>>()
    val missing = IntArray(definitions.size)
    val ready = ArrayDeque<Int>()
    for ((index, definition) in definitions.withIndex()) {
        for (input in definition.inputs.distinct()) {
            cancellation.charge(1)
            if (cancellation()) return null
            if (input !in columns) {
                missing[index]++
                waiting.getOrPut(input) { ArrayList() } += index
            }
        }
        if (missing[index] == 0) ready += index
    }
    fun release(output: Int): Boolean {
        for (index in waiting.remove(output).orEmpty()) {
            cancellation.charge(1)
            if (cancellation()) return false
            if (--missing[index] == 0) ready += index
        }
        return true
    }
    var fallback = 0
    while (columns.size < requested.size) {
        while (ready.isNotEmpty()) {
            cancellation.charge(1)
            if (cancellation()) return null
            val definition = definitions[ready.removeFirst()]
            if (definition.output in columns) continue
            val image = privateImage(definition, source, columns, cancellation) ?: continue
            val index = positions.getValue(definition.output)
            val inherited = definition.inputs.maxOfOrNull { columns.getValue(it).coordinate.buckets }
            val column = floatGridColumn(source, requested[index], source.numIntVars + index, policy, image, inherited)
                ?: return null
            columns[definition.output] = column
            if (!release(definition.output)) return null
        }
        if (columns.size == requested.size) break
        while (fallback < requested.size && requested[fallback].source.index in columns) {
            cancellation.charge(1)
            if (cancellation()) return null
            fallback++
        }
        val variable = requested[fallback]
        val column = floatGridColumn(source, variable, source.numIntVars + fallback, policy) ?: return null
        columns[variable.source.index] = column
        if (!release(variable.source.index)) return null
    }
    val result = ArrayList<FloatGridColumn>(requested.size)
    for (variable in requested) {
        cancellation.charge(1)
        if (cancellation()) return null
        result += columns.getValue(variable.source.index)
    }
    return if (cancellation()) null else result
}

private fun privateImage(
    definition: FloatWindowDefinition,
    source: Problem,
    columns: Map<Int, FloatGridColumn>,
    cancellation: Cancellation,
): FloatInterval? {
    fun range(id: Int): FloatInterval? = columns[id]?.coordinate?.let { FloatInterval(it.lower, it.upper) }
    fun interval(lower: Double, upper: Double): FloatInterval? =
        if (lower.isFinite() && upper.isFinite() && lower <= upper && (upper - lower).isFinite()) {
            FloatInterval(lower, upper)
        } else null
    return when (val factor = definition.relation) {
        is ContinuousProduct -> {
            val a = range(factor.a) ?: return null
            val b = range(factor.b) ?: return null
            val corners = listOf(a.lo * b.lo, a.lo * b.hi, a.hi * b.lo, a.hi * b.hi)
            interval(corners.min(), corners.max())
        }
        is Power -> {
            val input = range((factor.base as Power.Coordinate.Real).id.index) ?: return null
            val first = input.lo.pow(factor.exponent)
            val last = input.hi.pow(factor.exponent)
            interval(if (factor.exponent > 0 && factor.exponent and 1 == 0 && input.lo <= 0 && input.hi >= 0) 0.0
                else minOf(first, last), maxOf(first, last))
        }
        is RealAbs -> {
            val input = range(factor.operand) ?: return null
            interval(if (input.lo <= 0 && input.hi >= 0) 0.0 else minOf(abs(input.lo), abs(input.hi)),
                maxOf(abs(input.lo), abs(input.hi)))
        }
        is RealArrayMinMax -> {
            val inputs = ArrayList<FloatInterval>()
            for (input in factor.xs) {
                cancellation.charge(1)
                if (cancellation()) return null
                inputs += range(input) ?: return null
            }
            interval(if (factor.max) inputs.maxOf { it.lo } else inputs.minOf { it.lo },
                if (factor.max) inputs.maxOf { it.hi } else inputs.minOf { it.hi })
        }
        is RealElement -> if (factor.arrIsVars) {
            val inputs = ArrayList<FloatInterval>()
            for (input in factor.entries) {
                cancellation.charge(1)
                if (cancellation()) return null
                inputs += range(input) ?: return null
            }
            if (inputs.isEmpty()) null else interval(inputs.minOf { it.lo }, inputs.maxOf { it.hi })
        } else if (factor.constants.isEmpty()) null else interval(factor.constants.min(), factor.constants.max())
        is Linear -> {
            val constants = factor.realConstants ?: return null
            var lower = constants.bound
            var upper = constants.bound
            var outputCoefficient = 0.0
            for (index in factor.realVars.indices) {
                cancellation.charge(1)
                if (cancellation()) return null
                val id = factor.realVars[index]
                val coefficient = constants.realCoefficients.at(index)
                if (id == definition.output) outputCoefficient += coefficient else {
                    val input = range(id) ?: return null
                    val first = coefficient * input.lo
                    val last = coefficient * input.hi
                    lower -= maxOf(first, last)
                    upper -= minOf(first, last)
                }
            }
            for (index in factor.vars.indices) {
                cancellation.charge(1)
                if (cancellation()) return null
                val id = factor.vars[index]
                val coefficient = constants.intCoefficients.at(index)
                val first = coefficient * source.intBounds.lower(id)
                val last = coefficient * source.intBounds.upper(id)
                lower -= maxOf(first, last)
                upper -= minOf(first, last)
            }
            if (outputCoefficient == 0.0) null else {
                val first = lower / outputCoefficient
                val last = upper / outputCoefficient
                interval(minOf(first, last), maxOf(first, last))
            }
        }
        else -> null
    }
}
