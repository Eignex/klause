package com.eignex.klause.lp.bounding

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.LpCertificationObserver
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpClaim
import com.eignex.klause.lp.engine.LpDeductionCertificate
import com.eignex.klause.lp.engine.LpPhaseTimer
import com.eignex.klause.lp.engine.LpProjectionStop
import com.eignex.klause.lp.engine.LpRowBound
import com.eignex.klause.lp.engine.LpSolvePhase
import com.eignex.klause.lp.engine.MixedCertificateMeter
import com.eignex.klause.lp.engine.acceptNullable
import com.eignex.klause.lp.engine.ceilLong
import com.eignex.klause.lp.engine.exactBits
import com.eignex.klause.lp.engine.exactBounds
import com.eignex.klause.lp.engine.exactConstant
import com.eignex.klause.lp.engine.exactCost
import com.eignex.klause.lp.engine.exactShift
import com.eignex.klause.lp.engine.finiteExactInput
import com.eignex.klause.lp.engine.floorLong
import com.eignex.klause.lp.engine.hasIntegralObjective
import com.eignex.klause.lp.engine.mixedRowBounds
import com.eignex.klause.lp.engine.sourceObjective
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpExplanation
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.result.LpRoute
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.pollStop
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.CheckedLongOverflowException
import com.eignex.util.numbers.Int128
import com.eignex.util.numbers.addExact
import com.eignex.util.numbers.subExact
import kotlin.math.abs
import kotlin.math.nextUp

@Suppress("LongParameterList")
internal fun LpEngine.applyMixedRowBounds(
    relaxation: LpRelaxation,
    session: PropagationSession,
    sink: SolveStatsSink,
    learn: Boolean,
    cancellation: ExecutionControl,
    meter: MixedCertificateMeter,
    relaxer: CpToLpRelaxation? = null,
    onRefresh: () -> Unit = {},
): Boolean {
    var current = relaxation
    val initialWork = meter.work
    try {
        repeat(if (relaxer == null) 1 else 2) { pass ->
            if (cancellation.pollStop()) return false
            val affectedColumns = if (session.decisionLevel == 0) null
            else narrowedRowColumns(current, session, cancellation)
            val projected = solveContext.certificationPolicy.acceptNullable(
                LpCertifier.INTEGER,
                mixedRowBounds(
                    current.model, cancellation, sink.lp.certificationObserver(LpRoute.NODE), meter, affectedColumns,
                ),
            ).orEmpty()
            var changed = false
            if (observeNodeFixing(sink, LpSolvePhase.NODE_ROW_FIXING) {
                    applyRowBounds(current, projected, session, sink, learn, cancellation) { changed = true }
                }) return true
            if (!changed || relaxer == null || pass == 1 || cancellation.pollStop()) return false
            meter.reserve(current.model.numVars.toLong() * 16L + 128L)
            onRefresh()
            current = nodeRelaxation(relaxer, session) ?: return false
        }
        return false
    } catch (_: LpProjectionStop) {
        return false
    } finally {
        noteNodeOverhead(meter.work - initialWork)
    }
}

internal fun LpEngine.narrowedRowColumns(
    relaxation: LpRelaxation,
    session: PropagationSession,
    cancellation: ExecutionControl,
): IntArray {
    val columns = IntArrayList()
    noteNodeOverhead(relaxation.colVarId.size.toLong())
    for (column in relaxation.colVarId.indices) {
        if (cancellation.pollStop()) return intArrayOf()
        val variable = relaxation.colVarId[column]
        if (variable < 0 || relaxation.model.colContinuous[column]) continue
        val narrowed = if (relaxation.colIsBool[column]) {
            session.boolValue(variable) != null
        } else {
            val domain = session.intDomain(variable)
            val declared = problem.intBounds
            !declared.hasLower(variable) || !declared.hasUpper(variable) ||
                domain.min != declared.lower(variable) || domain.max != declared.upper(variable)
        }
        if (narrowed) columns.add(column)
    }
    return columns.toIntArray()
}

@Suppress("CyclomaticComplexMethod")
private fun LpEngine.applyRowBounds(
    relaxation: LpRelaxation,
    bounds: List<LpRowBound>,
    session: PropagationSession,
    sink: SolveStatsSink,
    learn: Boolean,
    cancellation: ExecutionControl,
    onFix: () -> Unit,
): Boolean {
    for (certificate in bounds) {
        if (cancellation.pollStop()) return false
        if (!certificate.belongsTo(relaxation.model)) continue
        val column = certificate.column
        val variable = relaxation.colVarId.getOrNull(column) ?: continue
        if (variable < 0 || relaxation.model.colContinuous[column]) continue
        val threshold = certificate.integerBound() ?: continue
        noteNodeOverhead(48L)
        val bool = relaxation.colIsBool[column]
        val pinned = if (bool) session.boolValue(variable) else null
        val minimum = if (bool) {
            if (pinned == true) 1L else 0L
        } else session.intDomain(variable).min
        val maximum = if (bool) {
            if (pinned == false) 0L else 1L
        } else session.intDomain(variable).max
        if ((certificate.upper && threshold >= maximum) || (!certificate.upper && threshold <= minimum)) continue
        noteNodeOverhead(certificate.supportCount.toLong() * LpNodeOverhead.FIXING)
        val support = LpExplanation.rowBoundReason(relaxation, certificate, session) ?: continue
        val reason = support.takeIf { learn && !bool }
        val result = when {
            bool && ((threshold < minimum && certificate.upper) ||
                (threshold > maximum && !certificate.upper)) -> {
                sink.lp.observePrune()
                LpClaim.BOUND.consumed(sink.lp.certificationObserver(LpRoute.NODE))
                return true
            }
            bool -> session.implyBool(variable, !certificate.upper)
            certificate.upper && reason != null -> session.implyIntAtMostWithReason(variable, threshold, reason)
            certificate.upper -> session.implyIntAtMost(variable, threshold)
            reason != null -> session.implyIntAtLeastWithReason(variable, threshold, reason)
            else -> session.implyIntAtLeast(variable, threshold)
        }
        if (result is PropagationResult.Unsat) {
            sink.lp.observePrune()
            LpClaim.BOUND.consumed(sink.lp.certificationObserver(LpRoute.NODE))
            return true
        }
        if (learn) lpNogoods?.let { pool ->
            val conclusion = when {
                bool -> Lit.make(variable, !certificate.upper)
                certificate.upper -> session.boundLeLit(variable, threshold, positive = true)
                else -> session.boundGeLit(variable, threshold, positive = true)
            }
            noteNodeOverhead(support.size.toLong() + 1L)
            val clause = intArrayOf(conclusion) + support
            val learned =
                if (pool.add(clause, registered = true) ) {
                    session.addLearnedClause(Clause(clause), lbd = clause.size, permanent = true)
                } else {
                    null
                }
            if (learned is PropagationResult.Unsat
            ) {
                sink.lp.observePrune()
                LpClaim.BOUND.consumed(sink.lp.certificationObserver(LpRoute.NODE))
                return true
            }
        }
        sink.lp.observeFix()
        onFix()
    }
    return false
}

internal inline fun observeNodeFixing(sink: SolveStatsSink, phase: LpSolvePhase, body: () -> Boolean): Boolean {
    val timer = LpPhaseTimer(sink.lp.certificationObserver(LpRoute.NODE), phase)
    val before = sink.lp.fixed.read().sum
    var returned = false
    var conflict = false
    try {
        conflict = body()
        returned = true
        return conflict
    } finally {
        val fixes = (sink.lp.fixed.read().sum - before).toInt()
        val outcome = when {
            !returned -> "FAILED"
            conflict -> "CONFLICT"
            fixes > 0 -> "FIXED"
            else -> "NONE"
        }
        timer.finish(outcome, steps = fixes)
    }
}

/**
 * Reduced-cost fixing from one [LpDeductionCertificate] in minimized source objective units. Moving
 * an integral column Δ steps from its minimizing endpoint raises the Lagrangian by `|reducedCost|·Δ`.
 * A verified integral objective uses `ceil(bound) - 1`; other objectives retain an enclosing rational
 * cutoff. A finite single-coordinate objective ceiling supplies a closed feasibility cutoff,
 * including before an incumbent exists. The allowed movement uses the unrounded gap divided by `|reducedCost|`.
 * With [learn] each integer fixing carries the LP dual-decomposition reason (the other
 * support columns' seated bounds + the incumbent bound + any dual-weighted non-global row's premises);
 * when the reason is inexpressible the fixing falls back to a reason-less level-local tightening (a
 * conflict-analysis leaf). Returns true if a reduction empties a domain (the node is then pruned).
 */
@Suppress("LongParameterList", "CyclomaticComplexMethod")
internal fun LpEngine.applySparseReducedCostFixing(
    relaxation: LpRelaxation,
    cert: LpDeductionCertificate,
    session: PropagationSession,
    bound: Double,
    sink: SolveStatsSink,
    objectiveVar: Int = -1,
    objectiveAscending: Boolean = true,
    learn: Boolean = false,
    cancellation: ExecutionControl = ExecutionControl.Never,
    objectiveCeiling: BigFraction? = null,
): Boolean {
    if (cancellation.pollStop() ) return false
    if ( !cert.belongsTo(relaxation.model, sink.lp.certificationObserver(LpRoute.NODE))) return false
    val sourceRange = sourceObjectiveRange(relaxation)
    val incumbentMax = if (!bound.isFinite()) {
        objectiveCeiling ?: return false
    } else if (sourceRange != null || permitsIntegralObjectiveCutoff(cert)) {
        val ceiling = enclosingCutoff(bound)?.ceilLong() ?: return false
        if (ceiling == Long.MIN_VALUE) return false
        BigFraction.ofLong(ceiling - 1L)
    } else {
        BigFraction.ofDouble(bound.nextUp()) ?: return false
    }
    val improvingMax = objectiveCeiling?.let { if (it < incumbentMax) it else incumbentMax } ?: incumbentMax
    val sourceConstant = relaxation.objectiveConstant
    val integralCutoff = ExactLpNumber.of(improvingMax).exactLong()
    if (integralCutoff != null) {
        if (!cert.improvingGapNonNegative(integralCutoff, sourceConstant)) return false
    } else {
        val lower = cert.exactObjectiveBound()
        val words = (maxOf(Long.SIZE_BITS, exactBits(listOf(improvingMax, lower))) + 63L) / 64L
        noteNodeOverhead(4L * words * words)
        if (improvingMax < lower + BigFraction.ofLong(sourceConstant)) return false
    }
    val rationalGap = if (cert.usesRationalArithmetic() || integralCutoff == null) {
        val lower = cert.exactObjectiveBound()
        val words = (maxOf(Long.SIZE_BITS, exactBits(listOf(improvingMax, lower))) + 63L) / 64L
        noteNodeOverhead(4L * words * words)
        improvingMax - BigFraction.ofLong(sourceConstant) - lower
    } else {
        null
    }
    val reasonSupport = if (learn && objectiveVar >= 0 && objectiveAscending && sourceRange != null) {
        reducedCostFixingReasons(relaxation, cert, session, objectiveVar, checkNotNull(improvingMax.floorLong()),
            sink.lp.certificationObserver(LpRoute.NODE))
            .also { sink.observeReason("FIXING", it != null) }
    } else {
        null
    }
    val canLearn = reasonSupport != null
    val columns = cert.fixingColumns()
    val size = columns?.size ?: relaxation.colVarId.size
    noteNodeOverhead(size.toLong() * LpNodeOverhead.FIXING)
    var cutUseObserved = false
    for (index in 0 until size) {
        if (cancellation.pollStop()) return false
        val col = columns?.get(index) ?: index
        if (col !in relaxation.colVarId.indices) continue
        val varId = relaxation.colVarId[col]
        if (varId < 0 || relaxation.model.colContinuous[col] ||
            relaxation.model.exactState?.model?.column(col)?.integral == false
        ) continue
        val isBool = relaxation.colIsBool[col]
        if (isBool && session.boolValue(varId) != null) continue
        val liveMin: Long
        val liveMax: Long
        if (isBool) {
            liveMin = 0L
            liveMax = 1L
        } else {
            val d = session.intDomain(varId)
            liveMin = d.min
            liveMax = d.max
        }
        if (liveMin == liveMax) continue
        val sign = cert.reducedCostSign(col)
        if (sign == 0) continue
        val bounds = relaxation.model.exactBounds(col)
        val side = (if (sign > 0) bounds.lower else bounds.upper) ?: continue
        val endpoint = ExactLpNumber.of(relaxation.model.exactShift(col) + side.number.value).exactLong() ?: continue
        noteNodeOverhead(cert.rationalFixingWork(col, improvingMax))
        val distance = try {
            if (sign > 0) subExact(liveMax, endpoint) else subExact(endpoint, liveMin)
        } catch (_: CheckedLongOverflowException) {
            null
        }
        if (distance != null && distance >= 0L) {
            val permitted = if (rationalGap != null) {
                cert.permitsMovementWithin(col, distance, rationalGap)
            } else {
                cert.permitsMovement(col, distance, checkNotNull(integralCutoff), sourceConstant)
            }
            if (permitted) continue
        }
        val dMax = if (rationalGap != null) {
            noteNodeOverhead(cert.rationalFixingWork(col, improvingMax))
            cert.fixStepsForGap(col, rationalGap)
        } else {
            cert.fixStepsAtCutoff(col, improvingMax, sourceConstant)
        }
        if (dMax == null) continue
        val res = when {
            // A positive reduced cost is minimized at the certificate model's lower endpoint.
            sign > 0 -> {
                val hi = try {
                    addExact(endpoint, dMax)
                } catch (_: CheckedLongOverflowException) {
                    continue
                }
                if (hi >= liveMax) continue
                when {
                    isBool -> session.implyBool(varId, false)
                    canLearn -> session.implyIntAtMostWithReason(varId, hi, reasonSupport.reasonFor(col))
                    else -> session.implyIntAtMost(varId, hi)
                }
            }

            // A negative reduced cost is minimized at the certificate model's upper endpoint.
            sign < 0 -> {
                val lo = try {
                    subExact(endpoint, dMax)
                } catch (_: CheckedLongOverflowException) {
                    continue
                }
                if (lo <= liveMin) continue
                when {
                    isBool -> session.implyBool(varId, true)
                    canLearn -> session.implyIntAtLeastWithReason(varId, lo, reasonSupport.reasonFor(col))
                    else -> session.implyIntAtLeast(varId, lo)
                }
            }

            else -> continue
        }
        if (!cutUseObserved) {
            observeCutUse(relaxation, cert, "REDUCED_COST_PROPAGATION")
            cutUseObserved = true
        }
        if (res is PropagationResult.Unsat) {
            sink.lp.observePrune()
            LpClaim.BOUND.consumed(sink.lp.certificationObserver(LpRoute.NODE))
            return true
        }
        sink.lp.observeFix()
        if (session.decisionLevel == 0) sink.lp.observeRootReducedCostFixes(1)
    }
    return false
}

internal fun LpEngine.singleObjectiveCeiling(
    relaxation: LpRelaxation,
    cancellation: ExecutionControl,
): BigFraction? {
    val model = relaxation.model
    if (!model.finiteExactInput()) return null
    noteNodeOverhead(model.numVars.toLong() * LpNodeOverhead.FIXING)
    var column = -1
    var coefficient = BigFraction.ZERO
    for (j in 0 until model.numVars) {
        if (cancellation.pollStop()) return null
        val cost = model.exactCost(j)
        if (cost.isZero) continue
        if (column >= 0 || j >= model.n) return null
        column = j
        coefficient = cost
    }
    if (column < 0) return null
    val bounds = model.exactBounds(column)
    val side = (if (coefficient.signum() > 0) bounds.upper else bounds.lower) ?: return null
    val objective = model.exactState?.model?.objective
    val scale = objective?.scale?.value ?: BigFraction.ONE
    val external = objective?.externalConstant?.value ?: BigFraction.ZERO
    val constant = model.exactConstant()
    val bits = exactBits(listOf(coefficient, side.number.value, constant, scale, external))
    if (bits > 128) return null
    val words = (maxOf(Long.SIZE_BITS, bits) + 63L) / 64L
    noteNodeOverhead(12L * words * words)
    val ceiling = (constant + coefficient * side.number.value) * scale.reciprocal() + external +
        BigFraction.ofLong(relaxation.objectiveConstant)
    return ceiling.takeIf { exactBits(listOf(it)) <= 128 }
}

/** One certificate's reusable support for local reduced-cost deductions under a named cutoff. */
internal class ReducedCostFixingReasons(
    private val supportCols: IntArray,
    private val supportLits: IntArray,
    private val incumbentLit: Int,
) {
    /** Reason for [col], excluding its own minimizing endpoint because that is the value being bounded. */
    fun reasonFor(col: Int): IntArray {
        val out = IntArrayList(supportCols.size + 1)
        for (k in supportCols.indices) if (supportCols[k] != col) out.add(supportLits[k])
        out.add(incumbentLit)
        return out.toIntArray()
    }
}

/**
 * Build the complete reusable premise set for one certificate: all dual-weighted row premises, every
 * structural column's minimizing endpoint with nonzero reduced cost (including basic columns), every further side
 * the certificate reads ([LpDeductionCertificate.extraCitedSides], kept in each fixing's reason, the fixed column's
 * own included), and the source-objective cutoff. For a single affine source objective `a·x + c`, the cutoff is
 * converted to the exact variable atom `x ≤ ⌊(improvingMax − c) / a⌋`; that atom must already hold. Null means some
 * premise cannot be named; the deduction may still be applied locally without a reusable reason.
 */
internal fun reducedCostFixingReasons(
    relaxation: LpRelaxation,
    cert: LpDeductionCertificate,
    session: PropagationSession,
    objectiveVar: Int,
    improvingMax: Long,
    observer: LpCertificationObserver? = null,
): ReducedCostFixingReasons? {
    if (!cert.belongsTo(relaxation.model, observer)) return null
    val objectiveVarMax = relaxation.objectiveVariableValue(objectiveVar, BigFraction.ofLong(improvingMax))
        ?.floorLong() ?: return null
    if (session.intDomain(objectiveVar).max > objectiveVarMax) return null
    val supportCols = IntArrayList()
    val supportLits = IntArrayList()
    val seen = IntHashSet()
    val premLits = IntArrayList()
    if (!LpExplanation.addDualRowPremiseLits(premLits, seen, relaxation, cert, session, observer)) return null
    for (k in 0 until premLits.size) {
        supportCols.add(-1) // row premise: part of every fixing's reason, never excluded
        supportLits.add(premLits[k])
    }
    for (col in relaxation.colVarId.indices) {
        for (lowerSide in LpExplanation.citedSides(cert, col)) {
            val premises = LpExplanation.boundPremiseLits(relaxation, session, col, lowerSide) ?: return null
            // Only the minimizing endpoint is the fixed column's own; a side another bound reads stays in its reason.
            val sign = cert.reducedCostSign(col)
            val own = relaxation.colVarId[col] >= 0 && sign != 0 && lowerSide == sign > 0
            for (literal in premises) {
                supportCols.add(if (own) col else -1)
                supportLits.add(literal)
            }
        }
    }
    return ReducedCostFixingReasons(
        supportCols.toIntArray(),
        supportLits.toIntArray(),
        session.boundLeLit(objectiveVar, objectiveVarMax, positive = false),
    )
}

internal fun LpEngine.permitsIntegralObjectiveCutoff(cert: LpDeductionCertificate): Boolean {
    val rows = cert.integralObjectiveRows() ?: return false
    return rows.isNotEmpty() || problem.numRealVars > 0
}

internal fun LpEngine.sourceObjectiveRange(relaxation: LpRelaxation): LongRange? {
    val model = relaxation.model
    if (!model.finiteExactInput() || !model.hasIntegralObjective()) return null
    val source = model.exactState?.model
    source?.objective?.let {
        if (it.scale.value != BigFraction.ONE || !it.externalConstant.value.isZero) return null
    }
    if (source == null && model.exactConstant() != BigFraction.ofLong(model.objConstant)) return null
    val objectiveConstant = ExactLpNumber.of(model.exactConstant()).exactLong() ?: return null
    fun costAt(column: Int): Long? = if (source != null) {
        source.objective.cost(column).exactLong()
    } else if (model.doubleView == null) {
        model.cost[column]
    } else {
        model.cost[column].takeIf { model.exactCost(column) == BigFraction.ofLong(it) }
    }
    val constant = Int128().also {
        it.addLong(objectiveConstant)
        it.addLong(relaxation.objectiveConstant)
    }
    for (j in 0 until model.numVars) {
        val cost = costAt(j) ?: return null
        if (cost == 0L) continue
        if (j >= model.n || model.colContinuous[j]) return null
        if (source == null && model.exactShift(j) != BigFraction.ofLong(model.loShift[j])) return null
        val origin = ExactLpNumber.of(model.exactShift(j)).exactLong() ?: return null
        val shift = Int128().also { it.addProduct(cost, origin) }
        constant.subtract(shift)
    }
    val minimum = constant.copy()
    val maximum = constant.copy()
    for (j in 0 until model.n) {
        val cost = costAt(j) ?: return null
        if (cost == 0L) continue
        val source = relaxation.colVarId.getOrNull(j) ?: return null
        val isBool = relaxation.colIsBool.getOrNull(j) ?: return null
        val lower: Long
        val upper: Long
        if (isBool) {
            if (source !in 0 until problem.numBoolVars) return null
            lower = 0L
            upper = 1L
        } else {
            if (source !in 0 until problem.numIntVars) return null
            if (!problem.intBounds.hasLower(source) || !problem.intBounds.hasUpper(source)) return null
            lower = problem.intBounds.lower(source)
            upper = problem.intBounds.upper(source)
        }
        minimum.addProduct(cost, if (cost > 0L) lower else upper)
        maximum.addProduct(cost, if (cost > 0L) upper else lower)
    }
    if (!minimum.fitsLong() || !maximum.fitsLong()) return null
    return minimum.toLong()..maximum.toLong()
}

internal fun enclosingCutoff(bound: Double): BigFraction? {
    if (!bound.isFinite()) return null
    // Wide Long incumbents can share a Double bin; its upper neighbor encloses every such source value.
    return BigFraction.ofDouble(if (abs(bound) >= EXACT_INTEGER_DOUBLE_LIMIT) bound.nextUp() else bound)
}

internal fun LpEngine.sourceObjectiveCutoff(
    relaxation: LpRelaxation,
    bound: Double,
    sourceRange: LongRange? = sourceObjectiveRange(relaxation),
): BigFraction? {
    if (sourceRange != null) return enclosingCutoff(bound)
    if (!bound.isFinite() || !relaxation.model.finiteExactInput()) return null
    return if (relaxation.model.hasIntegralObjective()) {
        null
    } else {
        // A continuous incumbent can lie above its Double rounding in any bin; the upper neighbor encloses it.
        BigFraction.ofDouble(bound.nextUp())
    }
}

internal fun LpRelaxation.objectiveVariableLowerBound(variable: Int, lower: BigFraction): Long? =
    objectiveVariableValue(variable, lower)?.ceilLong()

private fun LpRelaxation.objectiveVariableValue(variable: Int, value: BigFraction): BigFraction? {
    val column = intColOf.getOrNull(variable) ?: return null
    if (column !in 0 until model.n || colVarId[column] != variable || colIsBool[column]) return null
    val coefficient = model.exactCost(column)
    if (coefficient.signum() <= 0 || (0 until model.numVars).any { it != column && !model.exactCost(it).isZero }) {
        return null
    }
    val constant = BigFraction.ofLong(objectiveConstant) +
        model.sourceObjective(model.exactConstant() - coefficient * model.exactShift(column))
    val scale = model.exactState?.model?.objective?.scale?.value ?: BigFraction.ONE
    return (value - constant) * scale * coefficient.reciprocal()
}

internal const val EXACT_INTEGER_DOUBLE_LIMIT = 9007199254740992.0
