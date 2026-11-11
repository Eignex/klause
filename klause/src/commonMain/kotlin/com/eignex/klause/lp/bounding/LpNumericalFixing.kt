package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.engine.FLOAT_EVIDENCE_TOLERANCE
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpNumericalBound
import com.eignex.klause.lp.engine.ceilLong
import com.eignex.klause.lp.engine.floatingReducedCost
import com.eignex.klause.lp.engine.floorLong
import com.eignex.klause.lp.engine.lowerBoundDouble
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.util.numbers.CheckedLongOverflowException
import com.eignex.util.numbers.addExact
import com.eignex.util.numbers.subExact
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.nextDown
import kotlin.math.nextUp

@Suppress("LongParameterList", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
internal fun LpEngine.applyNumericalFixing(
    relaxation: LpRelaxation,
    duals: DoubleArray,
    lower: LpNumericalBound,
    bound: Double,
    session: PropagationSession,
    objectiveVar: Int,
    objectiveAscending: Boolean,
    objectiveCeiling: BigFraction?,
    cancellation: Cancellation,
    learn: Boolean,
): Boolean {
    if (cancellation() || !lower.belongsTo(relaxation.model) || !lower.usesDuals(duals) ||
        !lower.value.isFinite()
    ) return false
    val model = relaxation.model
    val mappingWork = 16L * (model.numVars.toLong() + model.n) + 64L
    if (mappingWork > numericalNodeWorkRemaining()) return false
    noteNumericalWork(mappingWork)
    if (cancellation()) return false
    val range = sourceObjectiveRange(relaxation)
    val incumbent = if (!bound.isFinite()) null else if (range != null) {
        val ceiling = sourceObjectiveCutoff(relaxation, bound, range)?.ceilLong() ?: return false
        if (ceiling == Long.MIN_VALUE) return false
        BigFraction.ofLong(ceiling - 1L)
    } else BigFraction.ofDouble(bound.nextUp())
    val maximum = if (incumbent == null) objectiveCeiling ?: return false
        else objectiveCeiling?.let { if (it < incumbent) it else incumbent } ?: incumbent
    val full = BigFraction.ofDouble(lower.value)?.plus(BigFraction.ofLong(relaxation.objectiveConstant)) ?: return false
    val upper = maximum.toDouble().nextUp()
    val floorBound = full.lowerBoundDouble()?.nextDown() ?: return false
    val tolerance = FLOAT_EVIDENCE_TOLERANCE * maxOf(1.0, abs(upper), abs(floorBound))
    val gap = ((upper - floorBound).nextUp() + tolerance).nextUp()
    if (!gap.isFinite() || gap < 0.0) return false
    val reason = if (learn && objectiveVar >= 0 && objectiveAscending && range != null) {
        numericalFixingReason(relaxation, session, objectiveVar, maximum, cancellation)
    } else null
    for (column in relaxation.colVarId.indices) {
        if (cancellation() || numericalNodeWorkRemaining() < 16L) return false
        noteNumericalWork(16L)
        val variable = relaxation.colVarId[column]
        if (variable < 0 || model.colContinuous[column] ||
            model.exactState?.model?.column(column)?.integral == false
        ) continue
        val isBool = relaxation.colIsBool[column]
        if (isBool && session.boolValue(variable) != null) continue
        val domain = if (isBool) null else session.intDomain(variable)
        val liveMin = domain?.min ?: 0L
        val liveMax = domain?.max ?: 1L
        if (liveMin == liveMax) continue
        val cost = floatingReducedCost(model, duals, column, numericalNodeWorkRemaining(), cancellation)
        noteNumericalWork(cost.work)
        if (cancellation() || cost.stop != null) return false
        val reduced = when {
            cost.lower?.let { it > 0.0 } == true -> cost.lower
            cost.upper?.let { it < 0.0 } == true -> cost.upper
            else -> continue
        } ?: continue
        val endpoint = model.integerFixingEndpoint(column, lower = reduced > 0.0) ?: continue
        val movement = (gap / abs(reduced)).nextUp()
        if (!movement.isFinite() || movement < 0.0 || movement >= Long.MAX_VALUE.toDouble()) continue
        val steps = floor(movement).toLong()
        val target = try {
            if (reduced > 0.0) addExact(endpoint, steps) else subExact(endpoint, steps)
        } catch (_: CheckedLongOverflowException) {
            continue
        }
        if (cancellation()) return false
        if ((reduced > 0.0 && target >= liveMax) || (reduced < 0.0 && target <= liveMin)) continue
        if (target < liveMin || target > liveMax) {
            sink.lp.observePrune()
            observeNumericalConsumption("FIXING_CONFLICT")
            return true
        }
        val result = when {
            isBool -> session.implyBool(variable, reduced < 0.0)
            reduced > 0.0 && reason != null -> session.implyIntAtMostWithReason(variable, target, reason)
            reduced > 0.0 -> session.implyIntAtMost(variable, target)
            reason != null -> session.implyIntAtLeastWithReason(variable, target, reason)
            else -> session.implyIntAtLeast(variable, target)
        }
        observeNumericalConsumption("REDUCED_COST_FIXING")
        if (result is PropagationResult.Unsat) {
            sink.lp.observePrune()
            return true
        }
        sink.lp.observeFix()
        if (session.decisionLevel == 0) sink.lp.observeRootReducedCostFixes(1)
    }
    return false
}

private fun LpEngine.numericalFixingReason(
    relaxation: LpRelaxation,
    session: PropagationSession,
    objectiveVar: Int,
    maximum: BigFraction,
    cancellation: Cancellation,
): IntArray? {
    val cutoff = relaxation.objectiveVariableValue(objectiveVar, maximum)?.floorLong() ?: return null
    if (session.intDomain(objectiveVar).max > cutoff) return null
    val premises = numericalPremiseClause(relaxation, session, cancellation) ?: return null
    return premises + session.boundLeLit(objectiveVar, cutoff, positive = false)
}

private fun LpModel.integerFixingEndpoint(column: Int, lower: Boolean): Long? {
    if ((if (lower) probeClampedLo else probeClampedHi).getOrElse(column) { false }) return null
    exactState?.model?.let { source ->
        val bounds = source.column(column).bounds
        val endpoint = (if (lower) bounds.lower else bounds.upper)?.number?.exactLong() ?: return null
        val origin = source.column(column).origin.exactLong() ?: return null
        return try { addExact(endpoint, origin) } catch (_: CheckedLongOverflowException) { null }
    }
    if ((lower && !hasFiniteLower(column)) || (!lower && !hasFiniteUpper(column))) return null
    if (doubleView == null) {
        return try { addExact(loShift[column], if (lower) 0L else upper[column]) }
        catch (_: CheckedLongOverflowException) { null }
    }
    val endpoint = loShiftD(column) + if (lower) lowerD(column) else upperD(column)
    if (!endpoint.isFinite() || abs(endpoint) >= 9007199254740992.0 || floor(endpoint) != endpoint) return null
    return endpoint.toLong()
}
