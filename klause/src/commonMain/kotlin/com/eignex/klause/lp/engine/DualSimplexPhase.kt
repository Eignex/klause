package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.IndexedVector
import com.eignex.klause.util.SparseSlices
import com.eignex.util.arrays.argsortBy
import com.eignex.util.collections.IntArrayList
import kotlin.math.abs

@Suppress("LongParameterList")
internal class DualSimplexPhase(
    basis: SimplexBasisLifecycle,
    private val workspace: SimplexWorkspace,
    private val pricingState: SimplexPricingState,
    private val artificialBounds: SimplexArtificialBounds,
    private val control: SimplexSolveControl,
    private val observations: SimplexObservations,
    private val operations: DualBasisOperations,
    private val monitor: SimplexPhaseMonitor,
    private val pricing: LpPricingOptions,
) {
    private val basicVar = basis.basicVar
    private val status = basis.status
    private val gamma = pricingState.gamma
    private val pricingOrder = pricingState.order
    private val pricingScratch = pricingState.scratch
    private val pivotRowEntries = workspace.pivotRowEntries
    private val enteringRatios = workspace.enteringRatios
    private val eligibleColumns = workspace.eligibleColumns
    private val eligibleOrdered = workspace.eligibleOrdered
    private val theoryColumns = workspace.theoryColumns
    private val touchedColumns = workspace.touchedColumns
    private val columnEpochs = workspace.columnEpochs
    private val work = control.work
    private val cancellation = control.cancellation
    private val progressTrace: SimplexProgressTrace? get() = monitor.trace
    private val tolerance = 1e-7
    private val feasibilityTolerance = 1e-6
    private val harrisTolerance: Double get() = 0.5 * tolerance
    private val minimumDelta: Double get() = 0.01 * tolerance
    var infeasibleRow: Int = -1
        private set
    var publishStoppedBound: Boolean = false
        private set
    var termination: LpFloatTermination? = null
        private set

    private inline fun <T> profiled(phase: SimplexProfilePhase, block: () -> T): T = monitor.profiled(phase, block)

    private fun stop(reason: LpFloatTermination, publishBound: Boolean = false): DualPhaseResult {
        termination = reason
        publishStoppedBound = publishBound
        return DualPhaseResult.STOPPED
    }

    private fun sideStatus(model: LpModel, column: Int, upper: Boolean): VarStatus =
        if (model.exactState != null && model.fixed(column)) {
            VarStatus.FIXED
        } else if (upper) {
            VarStatus.AT_UPPER
        } else {
            VarStatus.AT_LOWER
        }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount", "LongMethod", "LongParameterList")
    suspend fun run(
        model: LpModel,
        numerical: LpScalingView,
        enforced: BooleanArray?,
        progress: SolveProgress,
        finiteLower: BooleanArray?,
        finiteUpper: BooleanArray?,
        rowCols: Array<IntArray>,
        rowVals: Array<DoubleArray>,
        cachedValues: Boolean,
    ): DualPhaseResult {
        infeasibleRow = -1
        publishStoppedBound = false
        termination = null
        var useCached = cachedValues
        val beta = workspace.dualBeta
        val pivotRowEntry = pivotRowEntries // ρ·A_j per nonbasic, reused by the bound-flip ratio test
        val ratioBuf = enteringRatios // |d_j / a_j| per eligible nonbasic
        val elig = eligibleColumns
        val eligOrdered = eligibleOrdered // scratch for the ratio-ordered permutation of [elig]
        val theoryCandidates = theoryColumns
        workspace.beginDualPass()
        var epoch = 0
        // Whether an iterate's basic values are in [beta], so a solve that stops short can still hand
        // back its bound. The buffer is reused, and holds the last iterate the loop completed.
        var haveBeta = false
        iterations@ while (control.iterationsRemain(progress)) {
            monitor.checkpoint(progress)
            val iteration = progress.dualIterations++
            // Cooperative deadline, phased off the first iteration so a spent budget never starts.
            if (iteration % CANCEL_POLL == 0 && cancellation()) {
                return stop(
                    LpFloatTermination.CANCELLED,
                    haveBeta && model.exactState == null,
                )
            }
            // Work budget, checked before the iteration that would exceed it.
            if (control.workExhausted()) {
                return stop(LpFloatTermination.WORK, haveBeta)
            }
            // β = B⁻¹ (b − Σ_{j nonbasic at upper} A_j·u_j)
            if (!useCached) {
                operations.basicValues(beta)
            }
            useCached = false
            when (operations.refactorAtSafePoint()) {
                null -> Unit

                SimplexBasisRefactor.UNCHANGED -> {
                    pricingState.resetReference()
                    progress.dualIterations--
                    continue
                }

                SimplexBasisRefactor.BASIS_CHANGED -> return DualPhaseResult.RESTART

                SimplexBasisRefactor.FAILED -> return DualPhaseResult.FAILED
            }
            haveBeta = true
            monitor.sample(if (!artificialBounds.active) SimplexPhase.DUAL else SimplexPhase.BOXED_DUAL, beta)
            // Leaving: the most infeasible basic bound, scored by Devex — violation² / γ_i (approximate
            // dual steepest edge). Verify the chosen weight against the pivotal row which this iteration
            // needs anyway. Correcting an underestimate and reselecting is bounded by the row count:
            // every retry makes at least one selected weight exact in this reference frame.
            var r = -1
            var worst = 0.0
            var belowLower = false
            var pivotRow: IndexedVector
            while (true) {
                r = mostInfeasibleRow(model, numerical, beta, enforced, finiteLower, finiteUpper)
                if (r != -1) {
                    worst = leavingViolation
                    belowLower = leavingBelowLower
                }
                if (r == -1 && artificialBounds.active) {
                    // Optimal for the boxed LP. With no nonbasic seated on an artificial side it is optimal
                    // for the real LP as well: every basic value already meets the real bounds.
                    when {
                        artificialBounds.seats() == 0 -> artificialBounds.drop(model, finiteLower, finiteUpper)

                        // The dual never moves a dual-degenerate nonbasic, so widening could not clear such a seat.
                        artificialBounds.releaseDegenerate(
                            model, numerical, operations, tolerance,
                        ) > 0 -> continue@iterations

                        artificialBounds.widenings < MAX_ARTIFICIAL_WIDENINGS -> {
                            // Widening moves only nonbasic seats, so the basis stays dual feasible.
                            artificialBounds.widenSeated()
                            progress.dualIterations--
                            continue@iterations
                        }

                        else -> return DualPhaseResult.PRIMAL_HANDOFF
                    }
                }
                if (r == -1) {
                    return DualPhaseResult.OPTIMAL // primal feasible ⇒ optimal
                }
                // Pivot row ρ = e_r^T B⁻¹ = B⁻ᵀ e_r, kept indexed: it is the hypersparse vector of a
                // simplex iteration, and its true norm verifies the chosen Devex weight at no extra solve.
                pivotRow = operations.pivotalRow(r)
                val norm = pivotRow.nrm2()
                val trueWeight = norm * norm
                if (!(gamma[r] < DEVEX_WEIGHT_THRESHOLD * trueWeight)) break
                pricingState.correctWeight(r, trueWeight)
                observations.lastDevexWeightCorrections++
                if (cancellation()) {
                    return stop(
                        LpFloatTermination.CANCELLED,
                        model.exactState == null,
                    )
                }
                if (control.workExhausted()) {
                    return stop(LpFloatTermination.WORK, true)
                }
            }

            val y = operations.objectiveDuals()
            when (operations.refactorAtSafePoint()) {
                null -> Unit

                SimplexBasisRefactor.UNCHANGED -> {
                    pricingState.resetReference()
                    progress.dualIterations--
                    continue
                }

                SimplexBasisRefactor.BASIS_CHANGED -> return DualPhaseResult.RESTART

                SimplexBasisRefactor.FAILED -> return DualPhaseResult.FAILED
            }
            epoch++
            val touchedCount = formPivotRow(epoch, pivotRow, rowCols, rowVals)
            collectEligibleEntering(model, numerical, touchedCount, y, belowLower, enforced)
            val entering = if (elig.isEmpty()) {
                EnteringChoice.Selected(null)
            } else {
                chooseEntering(
                    elig,
                    eligOrdered,
                    theoryCandidates,
                    ratioBuf,
                    pivotRowEntry,
                    worst,
                    r,
                    enforced,
                    model,
                    numerical,
                )
            }
            if (entering == EnteringChoice.ResourceStopped) {
                observations.lastTheoryPricingResourceStops++
                val reason = if (cancellation()) LpFloatTermination.CANCELLED else LpFloatTermination.WORK
                return stop(reason, model.exactState == null)
            }
            val q = (entering as EnteringChoice.Selected).column
            if (q == null) {
                // An update chain can turn a tiny violation into a false infeasibility candidate even
                // though β is recomputed every iteration. Rebuild the factors—not merely the RHS solve—
                // and retry once per solve. A basis with no folded updates is already fresh.
                if (!progress.dualNumericalRecoveryTried && worst <= feasibilityTolerance &&
                    operations.updateCount > 0
                ) {
                    progress.dualNumericalRecoveryTried = true
                    if (cancellation()) {
                        return stop(
                            LpFloatTermination.CANCELLED,
                            model.exactState == null,
                        )
                    }
                    if (control.workExhausted()) {
                        return stop(LpFloatTermination.WORK, true)
                    }
                    when (operations.recoverNumerically()) {
                        SimplexBasisRefactor.UNCHANGED -> Unit
                        SimplexBasisRefactor.BASIS_CHANGED -> return DualPhaseResult.RESTART
                        SimplexBasisRefactor.FAILED -> return DualPhaseResult.FAILED
                    }
                    pricingState.resetReference()
                    progress.dualIterations-- // recovery is not a pivot and consumes no iteration allowance
                    continue
                }
                if (artificialBounds.active) {
                    // The ray proves only the boxed LP infeasible: an artificial seat or range may be what
                    // blocks every entering column. With no artificial seat the basis is dual feasible for the
                    // real bounds, so the iteration is re-derived on them; otherwise the primal decides.
                    if (artificialBounds.seats() > 0) {
                        if (artificialBounds.releaseDegenerate(
                                model, numerical, operations, tolerance,
                            ) > 0
                        ) continue
                        return DualPhaseResult.PRIMAL_HANDOFF
                    }
                    artificialBounds.drop(model, finiteLower, finiteUpper)
                    progress.dualIterations--
                    continue
                }
                infeasibleRow = r
                return DualPhaseResult.INFEASIBLE
            }

            val spike = operations.spike(q) // spike η = B⁻¹ A_q in the pre-pivot factorization
            if (abs(spike[r]) < tolerance) {
                // Numerically singular pivot. Counted before giving up: a solve lost here leaves no
                // result to read, so this is the only place the loss is visible.
                observations.smallPivotBails++
                return DualPhaseResult.NUMERICAL_FAILURE
            }
            pricingState.update(spike, r)
            progressTrace?.step(if (!artificialBounds.active) SimplexPhase.DUAL else SimplexPhase.BOXED_DUAL,
                ratioBuf[q], tolerance)
            val leaving = basicVar[r]
            status[leaving] = sideStatus(model, leaving, upper = !belowLower)
            basicVar[r] = q
            status[q] = VarStatus.BASIC
            observations.pivots++
            // Hand the solver back the spike and the pivotal row it just produced, so folding the pivot
            // in costs one FTRAN rather than a recomputation. A rebuild opens a fresh Devex reference
            // frame; a failed one gives up soundly.
            when (operations.foldPivot(r, q, leaving)) {
                PivotFold.UPDATED -> Unit
                PivotFold.REBUILT -> pricingState.resetReference()
                PivotFold.BASIS_CHANGED -> return DualPhaseResult.RESTART
                PivotFold.FAILED -> return DualPhaseResult.FAILED
            }
        }
        // Iteration budget spent. Same reasoning as the cancellation exit: the iterate bounds, so hand
        // it back rather than discarding the work.
        return stop(LpFloatTermination.PIVOTS, haveBeta)
    }

    // The violation and side of the row [mostInfeasibleRow] chose; unset when it found none.
    private var leavingViolation = 0.0
    private var leavingBelowLower = false

    /*
     * Leaving: the most infeasible basic bound, scored by Devex — violation² / γ_i (approximate dual steepest
     * edge); -1 when every basic value is within its bounds.
     */
    private fun mostInfeasibleRow(
        model: LpModel,
        numerical: LpScalingView,
        beta: DoubleArray,
        enforced: BooleanArray?,
        finiteLower: BooleanArray?,
        finiteUpper: BooleanArray?,
    ): Int = profiled(SimplexProfilePhase.PRICING) {
        var r = -1
        var bestScore = 0.0
        for (i in 0 until model.m) {
            val v = basicVar[i]
            // An unenforced row's basic slack is free: its value is never a violation.
            if (enforced != null && v >= model.n && !enforced[v - model.n]) continue
            val below = if (finiteLower?.get(v) ?: model.hasFiniteLower(v)) {
                artificialBounds.lowerAt(numerical, v) - beta[i]
            } else {
                Double.NEGATIVE_INFINITY
            }
            val above = if (finiteUpper?.get(v) ?: model.hasFiniteUpper(v)) {
                beta[i] - artificialBounds.upperAt(numerical, v)
            } else {
                Double.NEGATIVE_INFINITY
            }
            val isBelow = below >= above
            val viol = if (isBelow) below else above
            if (viol <= tolerance) continue
            val score = viol * viol / gamma[i]
            if (r == -1 || score > bestScore) {
                bestScore = score
                r = i
                leavingViolation = viol
                leavingBelowLower = isBelow
            }
        }
        return@profiled r
    }

    /*
     * ρ·A_j for every column ρ reaches, accumulated over the rows ρ stores, into [pivotRowEntries]; returns how
     * many columns it reached, listed in [touchedColumns]. Costs those rows' entries instead of nnz(A), which is
     * the whole point of ρ staying sparse. A column ρ misses has ρ·A_j = 0 exactly, so the eligibility pass loses
     * no candidate by skipping it. Reached columns are stamped with [epoch] rather than cleared: zeroing
     * [pivotRowEntries] between iterations would reintroduce the pass over every column this removes.
     */
    private fun formPivotRow(
        epoch: Int,
        pivotRow: IndexedVector,
        rowCols: Array<IntArray>,
        rowVals: Array<DoubleArray>,
    ): Int = profiled(SimplexProfilePhase.PRICING) {
        var touchedCount = 0
        pivotRow.forEachStored { i, rhoI ->
            val cols = rowCols[i]
            val vals = rowVals[i]
            work.add(cols.size)
            touchedCount = SparseSlices.scatterAxpy(
                rhoI, cols, 0, vals, 0, cols.size,
                pivotRowEntries, columnEpochs, epoch, touchedColumns, 0, touchedCount,
            )
        }
        return@profiled touchedCount
    }

    /*
     * Collect the dual-feasible entering candidates into [eligibleColumns] and their ratios into [enteringRatios];
     * eligibility is the sign rule that keeps reduced costs feasible as the leaving variable moves to its bound.
     */
    private fun collectEligibleEntering(
        model: LpModel,
        numerical: LpScalingView,
        touchedCount: Int,
        y: DoubleArray,
        belowLower: Boolean,
        enforced: BooleanArray?,
    ) = profiled(SimplexProfilePhase.PRICING) {
        eligibleColumns.clear()
        for (t in 0 until touchedCount) {
            val j = touchedColumns[t]
            if (status[j] == VarStatus.BASIC || model.fixed(j)) continue
            // An unenforced row's slack never enters — it is conceptually basic forever (and the
            // reconciliation above seats it, so a nonbasic one cannot appear mid-loop).
            if (enforced != null && j >= model.n && !enforced[j - model.n]) continue
            val a = pivotRowEntries[j]
            if (abs(a) < tolerance) continue
            val atLower = status[j] == VarStatus.AT_LOWER
            val eligible = if (status[j] == VarStatus.FREE) {
                true
            } else if (belowLower) {
                (atLower && a < 0) || (!atLower && a > 0)
            } else {
                (atLower && a > 0) || (!atLower && a < 0)
            }
            if (!eligible) continue
            progressTrace?.let { it.pricedColumns++ }
            enteringRatios[j] = abs((numerical.costD(j) - operations.dotColumn(y, j)) / a)
            eligibleColumns.add(j)
        }
    }

    /*
     * Pick the dual entering variable from the eligible set [elig] (ratios in [ratioBuf], pivot-row
     * coefficients in [pivotRowEntry]). The bound-flipping long step walks the eligible breakpoints in
     * ratio order, flipping each bounded nonbasic whose breakpoint is passed — accumulating `|a_j|·u_j`
     * toward the leaving variable's violation [delta] — until that capacity covers the violation or an
     * unbounded column is reached; that column enters. Harris admits finishing columns below the running
     * scaled bound `max(minimumDelta / |α|, ratio + harrisTolerance / |α|)` and takes the largest pivot.
     * A boxed column whose remaining range cannot finish the step stays flip-only and cannot enter.
     * Mutates [status] for the flips; sound regardless, since the basis is certified downstream.
     */
    @Suppress("LongParameterList")
    private fun chooseEntering(
        elig: IntArrayList,
        ordered: IntArray,
        theoryCandidates: IntArrayList,
        ratioBuf: DoubleArray,
        pivotRowEntry: DoubleArray,
        delta: Double,
        leavingRow: Int,
        enforced: BooleanArray?,
        model: LpModel,
        numerical: LpScalingView,
    ): EnteringChoice = profiled(SimplexProfilePhase.PRICING) {
        sortByRatio(elig, ordered, ratioBuf)
        var acc = 0.0
        var flipCount = 0
        for (idx in 0 until elig.size) {
            val j = elig[idx]
            val range = artificialBounds.boundRange(model, numerical, j)
            val cap = abs(pivotRowEntry[j]) * range
            val last = idx == elig.size - 1
            if (!last && range < Double.MAX_VALUE && acc + cap < delta - tolerance) {
                acc += cap
                flipCount++
            } else {
                val remaining = maxOf(delta - acc, 0.0)
                val best = harrisFinishingColumn(
                    elig, idx, remaining, theoryCandidates, ratioBuf, pivotRowEntry, model, numerical,
                )
                if (best != -1) {
                    val minimizeBoundSupport =
                        pricing.zeroObjective == LpZeroObjectivePricing.MIN_BOUND_SUPPORT &&
                            (0 until model.numVars).all { numerical.costD(it) == 0.0 }
                    val selected = if (minimizeBoundSupport) {
                        when (val theory = chooseTheoryEntering(theoryCandidates, leavingRow, enforced)) {
                            EnteringChoice.ResourceStopped -> return@profiled theory
                            is EnteringChoice.Selected -> theory.column ?: best
                        }
                    } else {
                        best
                    }
                    flipPassedBreakpoints(elig, flipCount)
                    if (ratioBuf[selected] > ratioBuf[j] + harrisTolerance) observations.lastHarrisMinistepSelections++
                    return@profiled EnteringChoice.Selected(selected)
                }
                return@profiled EnteringChoice.Selected(null)
            }
        }
        return@profiled EnteringChoice.Selected(null) // defensive: the loop handles the last element
    }

    // Stable ascending order by ratio.
    private fun sortByRatio(elig: IntArrayList, ordered: IntArray, ratioBuf: DoubleArray) {
        val order = argsortBy(elig.size, pricingOrder, pricingScratch) { a, b ->
            ratioBuf[elig[a]].compareTo(ratioBuf[elig[b]])
        }
        for (position in 0 until elig.size) ordered[position] = elig[order[position]]
        for (position in 0 until elig.size) elig[position] = ordered[position]
    }

    // The largest-pivot column from [from] on that can finish the [remaining] step within the Harris bound, or -1;
    // every finishing column with a usable pivot is listed in [theoryCandidates].
    @Suppress("LongParameterList")
    private fun harrisFinishingColumn(
        elig: IntArrayList,
        from: Int,
        remaining: Double,
        theoryCandidates: IntArrayList,
        ratioBuf: DoubleArray,
        pivotRowEntry: DoubleArray,
        model: LpModel,
        numerical: LpScalingView,
    ): Int {
        var harrisBound = Double.POSITIVE_INFINITY
        var best = -1
        var bestMag = -1.0
        theoryCandidates.clear()
        var k = from
        while (k < elig.size && ratioBuf[elig[k]] <= harrisBound) {
            val cand = elig[k]
            val mag = abs(pivotRowEntry[cand])
            val candRange = artificialBounds.boundRange(model, numerical, cand)
            val canFinish = candRange == Double.MAX_VALUE || mag * candRange >= remaining - tolerance
            val relaxed = maxOf(minimumDelta / mag, ratioBuf[cand] + harrisTolerance / mag)
            harrisBound = minOf(harrisBound, relaxed)
            if (canFinish) {
                if (mag > bestMag) {
                    bestMag = mag
                    best = cand
                }
                if (mag >= THEORY_PIVOT_MAGNITUDE_FLOOR) theoryCandidates.add(cand)
            }
            k++
        }
        return best
    }

    private fun flipPassedBreakpoints(elig: IntArrayList, flipCount: Int) {
        for (f in 0 until flipCount) {
            val flipped = elig[f]
            status[flipped] = if (status[flipped] == VarStatus.AT_LOWER) VarStatus.AT_UPPER else VarStatus.AT_LOWER
        }
    }

    private fun chooseTheoryEntering(
        candidates: IntArrayList,
        leavingRow: Int,
        enforced: BooleanArray?,
    ): EnteringChoice {
        observations.lastTheoryPricingAttempts++
        if (candidates.isEmpty()) {
            observations.lastTheoryPricingDeclines++
            return EnteringChoice.Selected(null)
        }
        addTheoryPricingWork(candidateOrderingWork(candidates.size))
        if (pricingResourceStopped()) return EnteringChoice.ResourceStopped
        val order = argsortBy(candidates.size, pricingOrder, pricingScratch) { a, b ->
            val left = candidates[a]
            val right = candidates[b]
            val sparsity = operations.columnNnz(left).compareTo(operations.columnNnz(right))
            if (sparsity != 0) sparsity else theoryTieRank(left).compareTo(theoryTieRank(right))
        }
        var best = -1
        var bestSupport = Int.MAX_VALUE
        var bestNnz = Int.MAX_VALUE
        var bestTie = Long.MAX_VALUE
        val samples = minOf(THEORY_SAMPLE_LIMIT, candidates.size)
        for (position in 0 until samples) {
            if (pricingResourceStopped()) return EnteringChoice.ResourceStopped
            val candidate = candidates[order[position]]
            val support = operations.sampleBoundSupport(candidate, leavingRow, enforced)
            when (support) {
                TheoryProbe.ArithmeticDeclined -> {
                    observations.lastTheoryPricingDeclines++
                    return EnteringChoice.Selected(null)
                }

                TheoryProbe.ResourceStopped -> return EnteringChoice.ResourceStopped

                is TheoryProbe.Success -> {
                    val nnz = operations.columnNnz(candidate)
                    val tie = theoryTieRank(candidate)
                    if (support.nonFreeBasics < bestSupport ||
                        (support.nonFreeBasics == bestSupport && nnz < bestNnz) ||
                        (support.nonFreeBasics == bestSupport && nnz == bestNnz && tie < bestTie)
                    ) {
                        best = candidate
                        bestSupport = support.nonFreeBasics
                        bestNnz = nnz
                        bestTie = tie
                    }
                }
            }
        }
        if (best == -1) {
            observations.lastTheoryPricingDeclines++
            return EnteringChoice.Selected(null)
        }
        observations.lastTheoryPricingSelections++
        observations.lastTheorySelectedColumn = best
        return EnteringChoice.Selected(best)
    }

    private fun addTheoryPricingWork(amount: Long) {
        work.add(amount)
        observations.lastTheoryPricingWorkOps += amount
    }

    private fun pricingResourceStopped(): Boolean = cancellation() || control.workExhausted()

    private fun candidateOrderingWork(size: Int): Long {
        var levels = 0
        var remaining = size - 1
        while (remaining > 0) {
            remaining = remaining ushr 1
            levels++
        }
        return size.toLong() * maxOf(levels, 1)
    }

    // SplitMix64 finalizer: G. L. Steele, D. Lea and C. H. Flood, "Fast splittable pseudorandom number
    // generators", OOPSLA 2014, https://doi.org/10.1145/2660193.2660195.
    private fun theoryTieRank(column: Int): Long {
        var value = pricing.tieSeed + GOLDEN_GAMMA * (column.toLong() + 1L)
        value = (value xor (value ushr 30)) * MIX_MULTIPLIER_1
        value = (value xor (value ushr 27)) * MIX_MULTIPLIER_2
        return value xor (value ushr 31)
    }

    private companion object {
        /* A Devex weight below this fraction of the computed pivotal-row norm is corrected. */
        const val DEVEX_WEIGHT_THRESHOLD: Double = 0.25

        /* Theory pricing samples only pivots large enough to be meaningful at simplex tolerance. */
        const val THEORY_PIVOT_MAGNITUDE_FLOOR: Double = 1e-6

        const val THEORY_SAMPLE_LIMIT: Int = 8

        const val GOLDEN_GAMMA: Long = -7046029254386353131L
        const val MIX_MULTIPLIER_1: Long = -4658895280553007687L
        const val MIX_MULTIPLIER_2: Long = -7723592293110705685L
        const val CANCEL_POLL: Int = 32

        /* Widening rounds before the basis goes to the primal: past 1e15 a seat swamps double precision. */
        const val MAX_ARTIFICIAL_WIDENINGS: Int = 2
    }
}

internal enum class DualPhaseResult {
    OPTIMAL,
    RESTART,
    PRIMAL_HANDOFF,
    STOPPED,
    INFEASIBLE,
    NUMERICAL_FAILURE,
    FAILED,
}

private sealed interface EnteringChoice {
    data class Selected(val column: Int?) : EnteringChoice

    data object ResourceStopped : EnteringChoice
}
