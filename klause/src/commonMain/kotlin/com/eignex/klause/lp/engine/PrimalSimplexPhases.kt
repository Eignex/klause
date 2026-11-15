package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisArithmeticException
import kotlin.math.abs

internal class PrimalSimplexPhases(
    basis: SimplexBasisLifecycle,
    workspace: SimplexWorkspace,
    private val control: SimplexSolveControl,
    private val observations: SimplexObservations,
    private val operations: PrimalBasisOperations,
    private val monitor: SimplexPhaseMonitor,
) {
    private val basicVar = basis.basicVar
    private val status = basis.status
    private val phaseOneBeta = workspace.phaseOneBeta
    private val phaseOneGradient = workspace.phaseOneGradient
    private val phaseOneDuals = workspace.phaseOneDuals
    private val alphaValues = workspace.alphaValues
    private val spikeVec = workspace.spikeVec
    private val work = control.work
    private val progressTrace: SimplexProgressTrace? get() = monitor.trace
    private var phaseOneRatio: PrimalPhaseOneRatio? = null
    private val tolerance = 1e-7
    private val feasibilityTolerance = 1e-6

    private fun stopped(reason: LpFloatTermination): PrimalPhaseResult {
        observations.termination = reason
        return PrimalPhaseResult.FAILED
    }

    private fun seat(source: LpScalingView, side: VarStatus, column: Int): Double = when (side) {
        VarStatus.AT_UPPER -> source.upperD(column)
        VarStatus.AT_LOWER, VarStatus.FIXED -> source.lowerD(column)
        VarStatus.FREE, VarStatus.BASIC -> 0.0
    }

    private fun sideStatus(model: LpModel, column: Int, upper: Boolean): VarStatus =
        if (model.exactState != null && model.fixed(column)) {
            VarStatus.FIXED
        } else if (upper) {
            VarStatus.AT_UPPER
        } else {
            VarStatus.AT_LOWER
        }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount", "LongMethod")
    suspend fun repair(
        model: LpModel,
        numerical: LpScalingView,
        progress: SolveProgress,
        phaseOneLongSteps: Boolean,
        phaseOneIncrementalValues: Boolean,
    ): PrimalPhaseResult {
        val m = model.m
        val numVars = model.numVars
        val beta = operations.basicValues(phaseOneBeta)
        if (control.workExhausted()) {
            observations.termination = LpFloatTermination.WORK
            return PrimalPhaseResult.FAILED
        }
        val gamma = phaseOneGradient
        val pi = phaseOneDuals
        val alphaBuf = alphaValues
        var valueUpdates = 0
        while (control.iterationsRemain(progress)) {
            monitor.checkpoint(progress)
            val iteration = progress.primalIterations++
            if (iteration % CANCEL_POLL == 0 && control.cancellation()) {
                observations.termination = LpFloatTermination.CANCELLED
                return PrimalPhaseResult.FAILED
            }
            if (control.workExhausted()) {
                observations.termination = LpFloatTermination.WORK
                return PrimalPhaseResult.FAILED
            }
            var w = phaseOneViolation(model, numerical, beta, gamma)
            if (w <= feasibilityTolerance && valueUpdates > 0) {
                operations.basicValues(beta)
                valueUpdates = 0
                if (control.workExhausted()) {
                    observations.termination = LpFloatTermination.WORK
                    return PrimalPhaseResult.FAILED
                }
                w = phaseOneViolation(model, numerical, beta, gamma)
            }
            monitor.sample(SimplexPhase.PRIMAL_FEASIBILITY, beta)
            if (w <= feasibilityTolerance) return PrimalPhaseResult.COMPLETE // feasible

            operations.transposeGradient(gamma, pi)
            when (operations.refactorAtSafePoint()) {
                null -> Unit

                // Fresh factors of the same basis after a routine trigger: re-derive β on them and price again,
                // spending no restart; the iteration it takes keeps a trigger at every safe point bounded.
                PrimalPhaseRefactor.UNCHANGED -> {
                    operations.basicValues(beta)
                    valueUpdates = 0
                    continue
                }

                PrimalPhaseRefactor.RESTART -> return PrimalPhaseResult.RESTART

                PrimalPhaseRefactor.BASIS_CHANGED -> return PrimalPhaseResult.BASIS_CHANGED

                PrimalPhaseRefactor.FAILED -> return PrimalPhaseResult.FAILED
            }
            // Entering reduces w: from lower if π·A_j > 0, from upper if π·A_j < 0; pick the steepest.
            var q = -1
            var qAtLower = true
            var best = tolerance
            monitor.profiled(SimplexProfilePhase.PRICING) {
                for (j in 0 until numVars) {
                    if (status[j] == VarStatus.BASIC || model.fixed(j)) continue
                    progressTrace?.let { it.pricedColumns++ }
                    val pj = operations.dotColumn(pi, j)
                    val atLower = status[j] == VarStatus.AT_LOWER || (status[j] == VarStatus.FREE && pj > 0.0)
                    val gain = if (atLower) pj else -pj
                    if (gain > best) {
                        best = gain
                        q = j
                        qAtLower = atLower
                    }
                }
            }
            if (q == -1) {
                if (valueUpdates > 0) {
                    operations.basicValues(beta)
                    valueUpdates = 0
                    continue
                }
                observations.termination = LpFloatTermination.INFEASIBLE_CANDIDATE
                return PrimalPhaseResult.INFEASIBLE
            }

            progressTrace?.lastEnteringGain = best
            val alpha = operations.spikeDense(q, alphaBuf)
            val dir = if (qAtLower) 1.0 else -1.0
            var tMax = numerical.boundRangeD(q)
            var leaving = -1
            var leavingToUpper = false
            for (i in 0 until m) {
                val rate = -alpha[i] * dir // dβ_i/dt
                if (abs(rate) < tolerance) continue
                val v = basicVar[i]
                val hi = if (model.hasFiniteUpper(v)) numerical.upperD(v) else Double.MAX_VALUE
                val lo = if (model.hasFiniteLower(v)) numerical.lowerD(v) else -Double.MAX_VALUE
                var t = Double.MAX_VALUE
                var toUpper = false
                when {
                    // Below its lower bound: a rising β_i reaches feasibility at l_i and may leave.
                    gamma[i] < 0 -> if (rate > 0) t = (lo - beta[i]) / rate

                    // Above its upper bound: a falling β_i reaches feasibility at u_i and may leave.
                    gamma[i] > 0 -> if (rate < 0) {
                        t = (beta[i] - hi) / -rate
                        toUpper = true
                    }

                    // Feasible: blocks at whichever bound it heads toward.
                    rate < 0 && model.hasFiniteLower(v) -> t = (beta[i] - lo) / -rate

                    rate > 0 && hi < Double.MAX_VALUE -> {
                        t = (hi - beta[i]) / rate
                        toUpper = true
                    }
                }
                if (t < tMax - tolerance) {
                    tMax = t
                    leaving = i
                    leavingToUpper = toUpper
                }
            }
            if (phaseOneLongSteps) {
                val ratio = phaseOneRatio ?: PrimalPhaseOneRatio(m).also { phaseOneRatio = it }
                val step = ratio.choose(
                    model, numerical, basicVar, beta, alpha, dir, numerical.boundRangeD(q), tolerance, work,
                )
                if (step != null) {
                    tMax = step.length
                    leaving = step.leaving
                    leavingToUpper = step.toUpper
                    progressTrace?.let { it.passedPhaseOneBreakpoints += step.passed }
                }
                val cancelled = control.cancellation()
                if (cancelled || control.workExhausted()) {
                    observations.termination = if (cancelled) LpFloatTermination.CANCELLED else LpFloatTermination.WORK
                    return PrimalPhaseResult.FAILED
                }
            }
            if (tMax >= Double.MAX_VALUE) return PrimalPhaseResult.FAILED // no blocker
            progressTrace?.step(SimplexPhase.PRIMAL_FEASIBILITY, tMax, tolerance, flip = leaving == -1)
            if (leaving == -1) {
                if (phaseOneIncrementalValues) advancePhaseOneValues(beta, dir * tMax)
                status[q] = if (qAtLower) VarStatus.AT_UPPER else VarStatus.AT_LOWER
                if (!phaseOneIncrementalValues || ++valueUpdates >= PRIMAL_VALUE_REFRESH_INTERVAL) {
                    operations.basicValues(beta)
                    valueUpdates = 0
                }
                continue
            }
            if (abs(alpha[leaving]) < tolerance) {
                observations.smallPivotBails++
                return PrimalPhaseResult.NUMERICAL_FAILURE
            }
            if (phaseOneIncrementalValues) {
                val enteringValue = seat(numerical, status[q], q) + dir * tMax
                advancePhaseOneValues(beta, dir * tMax, leaving, enteringValue)
            }
            val evicted = basicVar[leaving]
            status[evicted] = sideStatus(model, evicted, leavingToUpper)
            basicVar[leaving] = q
            status[q] = VarStatus.BASIC
            observations.pivots++
            when (operations.foldPivot(leaving, q, evicted)) {
                PivotFold.UPDATED -> {
                    if (!phaseOneIncrementalValues || ++valueUpdates >= PRIMAL_VALUE_REFRESH_INTERVAL) {
                        operations.basicValues(beta)
                        valueUpdates = 0
                    }
                }
                PivotFold.REBUILT -> {
                    operations.basicValues(beta)
                    valueUpdates = 0
                }
                PivotFold.BASIS_CHANGED -> return PrimalPhaseResult.BASIS_CHANGED
                PivotFold.FAILED -> return PrimalPhaseResult.FAILED
            }
        }
        observations.termination = LpFloatTermination.PIVOTS
        return PrimalPhaseResult.FAILED // budget exhausted
    }

    private fun phaseOneViolation(
        model: LpModel,
        numerical: LpScalingView,
        beta: DoubleArray,
        gradient: DoubleArray,
    ): Double =
        monitor.profiled(SimplexProfilePhase.FEASIBILITY_SCAN) {
            var violation = 0.0
            for (i in 0 until model.m) {
                val column = basicVar[i]
                val lower = if (model.hasFiniteLower(column)) numerical.lowerD(column) else -Double.MAX_VALUE
                val upper = if (model.hasFiniteUpper(column)) numerical.upperD(column) else Double.MAX_VALUE
                gradient[i] = when {
                    beta[i] < lower - feasibilityTolerance -> {
                        violation += lower - beta[i]
                        -1.0
                    }
                    beta[i] > upper + feasibilityTolerance -> {
                        violation += beta[i] - upper
                        1.0
                    }
                    else -> 0.0
                }
            }
            violation
        }

    private fun advancePhaseOneValues(
        beta: DoubleArray,
        delta: Double,
        leaving: Int = -1,
        enteringValue: Double = 0.0,
    ) {
        if (delta != 0.0) {
            val m = beta.size
            // A dimension charge keeps the allowance independent of the backend's indexed storage.
            work.add(2L * m)
            spikeVec.forEachStored { row, value ->
                if (row != leaving) {
                    beta[row] -= value * delta
                    if (!beta[row].isFinite()) throw BasisArithmeticException("nonfinite primal value")
                }
            }
        }
        if (leaving >= 0) {
            if (!enteringValue.isFinite()) throw BasisArithmeticException("nonfinite entering value")
            work.add(1)
            beta[leaving] = enteringValue
        }
        progressTrace?.let { it.primalValueUpdates++ }
    }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount", "LongMethod")
    suspend fun optimize(
        model: LpModel,
        numerical: LpScalingView,
        progress: SolveProgress,
        beta: DoubleArray,
        primalPricingTolerance: Double,
    ): PrimalPhaseResult {
        val m = model.m
        val numVars = model.numVars
        val blandStall = 2 * (m + numVars) + BLAND_STALL_BASE
        val alphaBuf = alphaValues
        while (control.iterationsRemain(progress)) {
            monitor.checkpoint(progress)
            val iteration = progress.primalIterations++
            if (iteration % CANCEL_POLL == 0 && control.cancellation()) return stopped(LpFloatTermination.CANCELLED)
            if (control.workExhausted()) return stopped(LpFloatTermination.WORK)
            // Floating Bland ordering is a bounded anti-cycling heuristic, not a termination proof.
            val bland = progress.primalDegenerate >= blandStall
            if (bland && !progress.primalBlandActive) observations.numericalMetrics.primalBlandEntries++
            progress.primalBlandActive = bland
            val y = operations.objectiveDuals()
            when (operations.refactorAtSafePoint()) {
                null -> Unit

                PrimalPhaseRefactor.UNCHANGED -> {
                    operations.basicValues(beta)
                    continue
                }

                PrimalPhaseRefactor.RESTART -> return PrimalPhaseResult.RESTART

                PrimalPhaseRefactor.BASIS_CHANGED -> return PrimalPhaseResult.RESTART

                PrimalPhaseRefactor.FAILED -> return PrimalPhaseResult.FAILED
            }
            var q = -1
            var qAtLower = true
            var best = primalPricingTolerance
            monitor.sample(SimplexPhase.PRIMAL_OBJECTIVE, beta)
            monitor.profiled(SimplexProfilePhase.PRICING) {
                for (j in 0 until numVars) {
                    if (status[j] == VarStatus.BASIC || model.fixed(j)) continue
                    progressTrace?.let { it.pricedColumns++ }
                    val dj = numerical.costD(j) - operations.dotColumn(y, j)
                    val atLower = status[j] == VarStatus.AT_LOWER || (status[j] == VarStatus.FREE && dj < 0.0)
                    // From lower, increasing improves iff d_j < 0; from upper, decreasing improves iff d_j > 0.
                    val gain = if (atLower) -dj else dj
                    if (gain <= primalPricingTolerance) continue
                    if (bland) {
                        q = j // first (lowest-index) improving column
                        qAtLower = atLower
                        break
                    }
                    if (gain > best) {
                        best = gain
                        q = j
                        qAtLower = atLower
                    }
                }
            }
            if (q == -1) return PrimalPhaseResult.COMPLETE // no improving column ⇒ optimal

            progressTrace?.lastEnteringGain = best
            val alpha = operations.spikeDense(q, alphaBuf) // α = B⁻¹ A_q
            val dir = if (qAtLower) 1.0 else -1.0 // x_q moves by dir·t, t ≥ 0
            // Ratio test with the entering variable's own bound flip as a candidate blocker.
            var tMax = numerical.boundRangeD(q)
            var leaving = -1
            var leavingToUpper = false
            var leavingVar = Int.MAX_VALUE
            for (i in 0 until m) {
                val rate = -alpha[i] * dir // dβ_i/dt
                var t = Double.MAX_VALUE
                var toUpper = false
                if (rate < -tolerance && model.hasFiniteLower(basicVar[i])) {
                    t = (beta[i] - numerical.lowerD(basicVar[i])) / -rate
                } else if (rate > tolerance && model.hasFiniteUpper(basicVar[i])) {
                    t = (numerical.upperD(basicVar[i]) - beta[i]) / rate // β_i rises to its upper bound
                    toUpper = true
                }
                if (t == Double.MAX_VALUE) continue
                // Strictly shorter step, or — under Bland's — an equal step leaving a lower-indexed variable.
                val accept =
                    t < tMax - tolerance ||
                        (bland && leaving != -1 && t <= tMax + tolerance && basicVar[i] < leavingVar)
                if (accept) {
                    if (t < tMax) tMax = t
                    leaving = i
                    leavingToUpper = toUpper
                    leavingVar = basicVar[i]
                }
            }
            if (tMax >= Double.MAX_VALUE) {
                // This basis is primal feasible; an unbounded objective does not make it dual feasible.
                return PrimalPhaseResult.UNBOUNDED
            }
            if (leaving == -1) {
                // The entering variable reaches its opposite bound first: flip it, no basis change.
                progressTrace?.step(SimplexPhase.PRIMAL_OBJECTIVE, tMax, tolerance, flip = true)
                status[q] = if (qAtLower) VarStatus.AT_UPPER else VarStatus.AT_LOWER
                operations.basicValues(beta)
                continue
            }
            if (abs(alpha[leaving]) < tolerance) {
                observations.smallPivotBails++
                return PrimalPhaseResult.NUMERICAL_FAILURE // numerically singular pivot
            }
            progress.primalDegenerate = if (tMax <= tolerance) progress.primalDegenerate + 1 else 0
            progressTrace?.step(SimplexPhase.PRIMAL_OBJECTIVE, tMax, tolerance)
            val evicted = basicVar[leaving]
            status[evicted] = sideStatus(model, evicted, leavingToUpper)
            basicVar[leaving] = q
            status[q] = VarStatus.BASIC
            observations.pivots++
            when (operations.foldPivot(leaving, q, evicted)) {
                PivotFold.UPDATED, PivotFold.REBUILT -> Unit
                PivotFold.BASIS_CHANGED -> return PrimalPhaseResult.RESTART
                PivotFold.FAILED -> return PrimalPhaseResult.FAILED
            }
            operations.basicValues(beta)
        }
        return stopped(LpFloatTermination.PIVOTS) // budget exhausted
    }

    private companion object {
        const val BLAND_STALL_BASE: Int = 50
        const val PRIMAL_VALUE_REFRESH_INTERVAL: Int = 8
        const val CANCEL_POLL: Int = 32
    }
}
