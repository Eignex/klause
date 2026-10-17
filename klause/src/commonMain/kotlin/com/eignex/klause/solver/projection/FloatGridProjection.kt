package com.eignex.klause.solver.projection

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.GridAbs
import com.eignex.klause.factor.arithmetic.GridCoordinate
import com.eignex.klause.factor.arithmetic.GridPower
import com.eignex.klause.factor.arithmetic.GridProduct
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.arithmetic.RealAbs
import com.eignex.klause.factor.arithmetic.RealFiniteDomain
import com.eignex.klause.factor.arithmetic.RealArrayMinMax
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.table.RealElement
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.lowering.approximateGridValue
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.encodingPreparationScope
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.FloatInterval

internal data class FloatGridVariable(
    val source: SourceRealId,
    val buckets: Int? = null,
    val auxiliary: Boolean = false,
    val absentPin: FloatGridAbsentPin? = null,
    val preserveBuckets: Boolean = false,
)

internal class FloatGridRequest(
    val source: Problem,
    variables: List<FloatGridVariable>,
    val owner: Any = Any(),
    truncatedRows: Set<Int> = emptySet(),
) {
    val variables = variables.toList()
    val truncatedRows = truncatedRows.toSet()
}

internal enum class FloatGridDecline {
    POLICY, COORDINATES, OPEN_INTEGER, GEOMETRY, FACTOR, SCALING, RESOURCE,
}

internal sealed interface FloatGridPreparation {
    class Prepared(val problem: Problem, val mapping: FloatGridMapping) : FloatGridPreparation
    class Declined(val cause: FloatGridDecline, val sourceFactor: Int? = null) : FloatGridPreparation
}

internal fun prepareFloatGrid(request: FloatGridRequest, cancellation: Cancellation): FloatGridPreparation {
    val stop = cancellation.encodingPreparationScope()
    val source = request.source
    if (stop()) return FloatGridPreparation.Declined(FloatGridDecline.RESOURCE)
    val policy = source.semantics.effective.floats as? FloatSemantics.Grid
        ?: return FloatGridPreparation.Declined(FloatGridDecline.POLICY)
    if (source.semantics.declared.floats == null) return FloatGridPreparation.Declined(FloatGridDecline.POLICY)
    val scale = policy.linearScale.toDouble()
    if (scale >= -Long.MIN_VALUE.toDouble() || scale.toLong() != policy.linearScale) {
        return FloatGridPreparation.Declined(FloatGridDecline.SCALING)
    }
    val requested = request.variables
    if (requested.isEmpty() || source.numIntVars.toLong() + requested.size > Int.MAX_VALUE) {
        return FloatGridPreparation.Declined(FloatGridDecline.COORDINATES)
    }
    val selected = BooleanArray(source.numRealVars)
    for (variable in requested) {
        stop.charge(1)
        if (stop()) return FloatGridPreparation.Declined(FloatGridDecline.RESOURCE)
        val id = variable.source.index
        if (id !in selected.indices || selected[id] || (variable.buckets ?: policy.buckets) <= 0) {
            return FloatGridPreparation.Declined(FloatGridDecline.COORDINATES)
        }
        selected[id] = true
    }
    for (id in 0 until source.numIntVars) {
        stop.charge(1)
        if (stop()) return FloatGridPreparation.Declined(FloatGridDecline.RESOURCE)
        if (!source.intBounds.hasLower(id) || !source.intBounds.hasUpper(id)) {
            return FloatGridPreparation.Declined(FloatGridDecline.OPEN_INTEGER)
        }
    }
    for ((id, factor) in source.factors.withIndex()) {
        if (!preflightFloatFactor(source, factor, selected, stop)) {
            return FloatGridPreparation.Declined(
                if (stop()) FloatGridDecline.RESOURCE else FloatGridDecline.FACTOR, id,
            )
        }
    }
    val primaryColumns = prepareFloatGridColumns(source, requested, policy, stop)
        ?: return FloatGridPreparation.Declined(if (stop()) FloatGridDecline.RESOURCE else FloatGridDecline.GEOMETRY)
    val optional = prepareFloatGridAbsentPins(source, requested, primaryColumns, stop)
        ?: return FloatGridPreparation.Declined(if (stop()) FloatGridDecline.RESOURCE else FloatGridDecline.GEOMETRY)
    val columns = optional.columns
    val bySource = columns.associateBy { it.source.index }
    val retained = IntArray(source.numRealVars) { -1 }
    val lower = ArrayList<Double>()
    val upper = ArrayList<Double>()
    for (id in retained.indices) {
        stop.charge(1)
        if (stop()) return FloatGridPreparation.Declined(FloatGridDecline.RESOURCE)
        if (!selected[id]) {
            retained[id] = lower.size
            lower += source.realLower[id]
            upper += source.realUpper[id]
        }
    }
    val rename = VarRemap(IntArray(source.numBoolVars) { it }, IntArray(source.numIntVars) { it }, retained)
    val factors = ArrayList<Factor>()
    val origins = ArrayList<Int>()
    for ((id, factor) in source.factors.withIndex()) {
        stop.charge(1)
        if (stop()) return FloatGridPreparation.Declined(FloatGridDecline.RESOURCE)
        val output = if (factor.variables.reals.none { selected[it] }) {
            listOf(if (factor.variables.reals.isEmpty()) factor else factor.remap(rename))
        } else {
            val pin = optional.pinRows[id]
            (if (pin == null) lowerFloatGridFactor(factor, bySource, policy, stop, id in request.truncatedRows)
            else lowerGridPin(factor, pin.first, pin.second))
                ?: return FloatGridPreparation.Declined(
                    if (stop()) FloatGridDecline.RESOURCE else FloatGridDecline.SCALING, id,
                )
        }
        for (converted in output) {
            stop.charge(1)
            if (stop()) return FloatGridPreparation.Declined(FloatGridDecline.RESOURCE)
            factors += converted
            origins += id
        }
    }
    factors.addAll(optional.factors)
    origins.addAll(optional.origins)
    val privateCoordinates = columns.flatMap { it.privateCoordinates() }.associateBy { it.variable }
    val domains = Array(source.numIntVars + privateCoordinates.size) { id ->
        stop.charge(1)
        if (stop()) return FloatGridPreparation.Declined(FloatGridDecline.RESOURCE)
        if (id < source.numIntVars) source.declaredIntDomains.finiteDomain(id)
        else IntDomain(0L, privateCoordinates.getValue(id).buckets - 1L, source.settings.storage)
    }
    if (stop()) return FloatGridPreparation.Declined(FloatGridDecline.RESOURCE)
    val target = Problem(
        optional.booleans, domains.size, domains, factors.toTypedArray(),
        impliedFactorMask = source.impliedFactorMask?.let { mask -> BooleanArray(origins.size) { mask[origins[it]] } },
        hasSymmetryBreaking = source.hasSymmetryBreaking,
        numRealVars = lower.size, realLower = lower.toDoubleArray(), realUpper = upper.toDoubleArray(),
        settings = source.settings, semantics = source.semantics,
    )
    stop.charge(columns.size.toLong() + retained.size + origins.size)
    if (stop()) return FloatGridPreparation.Declined(FloatGridDecline.RESOURCE)
    val mapping = FloatGridMapping(source, target, columns, retained, origins.toIntArray(), request.owner, stop)
    return if (stop()) FloatGridPreparation.Declined(FloatGridDecline.RESOURCE)
    else FloatGridPreparation.Prepared(target, mapping)
}

private fun preflightFloatFactor(
    source: Problem,
    factor: Factor,
    selected: BooleanArray,
    cancellation: Cancellation,
): Boolean {
    cancellation.charge(1)
    if (cancellation()) return false
    val roles = factor.variables
    for (id in factor.boolVars) {
        cancellation.charge(1)
        if (cancellation() || id !in 0 until source.numBoolVars) return false
    }
    for (id in roles.ints) {
        cancellation.charge(1)
        if (cancellation() || id !in 0 until source.numIntVars) return false
    }
    var projected = 0
    for (id in roles.reals) {
        cancellation.charge(1)
        if (cancellation() || id !in selected.indices) return false
        if (selected[id]) projected++
    }
    if (projected == 0) return true
    if (projected != roles.reals.size) return false
    return when (factor) {
        is Linear, is ReifiedRealLinear, is RealAbs, is RealArrayMinMax, is RealElement, is RealFiniteDomain -> true
        is ContinuousProduct -> factor.finiteChoices?.let { choices ->
            !choices.hasGuards || (choices.size == 1 && source.realLower[choices.operand] == choices.valueAt(0) &&
                source.realUpper[choices.operand] == choices.valueAt(0))
        } ?: true
        is Power -> factor.base is Power.Coordinate.Real
        else -> false
    }
}

internal fun floatGridColumn(
    source: Problem,
    variable: FloatGridVariable,
    target: Int,
    policy: FloatSemantics.Grid,
    image: FloatInterval? = null,
    inheritedBuckets: Int? = null,
): FloatGridColumn? {
    val id = variable.source.index
    if (source.realLower[id].isNaN() || source.realUpper[id].isNaN() ||
        source.realLower[id] == Double.POSITIVE_INFINITY || source.realUpper[id] == Double.NEGATIVE_INFINITY
    ) return null
    val origin = (if (source.realLower[id].isFinite()) {
        BigFraction.ofDouble(source.realLower[id])
    } else if (image != null) {
        BigFraction.ofDouble(image.lo)
    } else {
        policy.unrangedBounds.lower?.let { BigFraction.of(it.numerator, it.denominator) }
    }) ?: return null
    val upper = (if (source.realUpper[id].isFinite()) {
        BigFraction.ofDouble(source.realUpper[id])
    } else if (image != null) {
        BigFraction.ofDouble(image.hi)
    } else {
        policy.unrangedBounds.upper?.let { BigFraction.of(it.numerator, it.denominator) }
    }) ?: return null
    if (origin > upper) return null
    val buckets = if (origin == upper && !variable.preserveBuckets) 1
        else variable.buckets ?: inheritedBuckets ?: policy.buckets
    val step = if (buckets == 1) BigFraction.ZERO
    else (upper - origin) * BigFraction.ofLong(buckets - 1L).reciprocal()
    val lo = approximateGridValue(origin)
    val hi = approximateGridValue(upper)
    if (!lo.isFinite() || !hi.isFinite() || !(hi - lo).isFinite() ||
        (!origin.isZero && lo == 0.0) || (!upper.isZero && hi == 0.0) ||
        (!step.isZero && (!approximateGridValue(step).isFinite() || approximateGridValue(step) == 0.0))
    ) return null
    if ((source.realLower[id].isFinite() && lo != source.realLower[id]) ||
        (source.realUpper[id].isFinite() && hi != source.realUpper[id])
    ) return null
    return FloatGridColumn(variable.source, GridCoordinate(target, lo, hi, buckets), origin, step)
}

private fun lowerFloatGridFactor(
    factor: Factor,
    columns: Map<Int, FloatGridColumn>,
    policy: FloatSemantics.Grid,
    cancellation: Cancellation,
    truncate: Boolean,
): List<Factor>? {
    fun coordinate(id: Int): GridCoordinate = columns.getValue(id).coordinate
    return when (factor) {
        is Linear -> lowerGridRow(factor, columns, policy.linearScale, cancellation, truncate)
        is ReifiedRealLinear -> lowerGridRow(factor, columns, policy.linearScale, cancellation, truncate)
        is RealAbs -> {
            val input = coordinate(factor.operand)
            val output = coordinate(factor.result)
            val image = prepareGridAbsImage(input, output, cancellation) ?: return null
            listOf(GridAbs(input.variable, output.variable, input.lower, input.upper, input.buckets,
                output.lower, output.upper, output.buckets, image))
        }
        is ContinuousProduct -> {
            val product = GridProduct(coordinate(factor.a), coordinate(factor.b), coordinate(factor.result))
            val choices = factor.finiteChoices?.takeUnless { it.hasGuards }
            val membership = if (choices == null) emptyList() else lowerGridFiniteValues(choices.size,
                choices::valueAt, columns.getValue(choices.operand), cancellation) ?: return null
            listOf(product) + membership
        }
        is Power -> listOf(GridPower(coordinate((factor.base as Power.Coordinate.Real).id.index), factor.exponent,
            coordinate((factor.result as Power.Coordinate.Real).id.index)))
        is RealArrayMinMax -> listOf(lowerGridMinMax(factor, columns, policy.linearScale, cancellation) ?: return null)
        is RealElement -> lowerGridElement(factor, columns, cancellation)
        is RealFiniteDomain -> lowerGridFiniteDomain(factor, columns.getValue(factor.variable), cancellation)
        else -> null
    }
}
