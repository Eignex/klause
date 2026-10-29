package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList

/**
 * Component-decomposed LP solve: when the structural columns split into independent blocks (two
 * columns are coupled iff they share a row of the union sparsity), each block is its own [LpModel]
 * solved by its own engine, and the block results stitch back into one full-model [FloatLpResult].
 * Unlike a neighborhood restriction this is **exact**, not a relaxation — no dropped row shares a
 * variable with a kept one — so the stitched optimum, primal point, and dual vector are precisely
 * what the monolithic solve would produce, while each block's basis factorization costs a fraction
 * of the monolithic one. An infeasible block certifies the whole model infeasible: its Farkas ray
 * scattered to full row length (zeros on the other blocks' rows) is a valid full-model ray.
 *
 * Structural columns in no row ([isolated]) need no solve at all: each rides to whichever of its
 * bounds its cost prefers; an isolated column that prefers an infinite bound makes the LP unbounded,
 * reported as a null result with an unbounded-candidate termination, as the monolithic engine does.
 *
 * The warm-start handle is not split across blocks — a decomposed solve cold-starts each block
 * (small by construction, which is the point); the warm contract permits this (it changes only the
 * pivot path, never the result).
 */
internal class ComponentLpSolver(
    private var model: LpModel,
    private var parts: List<LpNeighborhood>,
    private var solvers: List<LpSolver>,
    private val isolated: IntArray,
) : RetainedComponentLpSolverCapability,
    ResumableLpSolver,
    TableauCutSolver {
    private val reusable = arrayOfNulls<FloatLpResult>(parts.size)
    private class CheckedBound(val policy: LpCertificationPolicy, val bound: CertifiedLpBound)
    private val checkedBounds = arrayOfNulls<CheckedBound>(parts.size)
    private var activeSolve: LpSolveCursor<FloatLpResult?>? = null
    private var activeChild: LpSolver? = null
    private var solveStarted = false
    private val certificationKey = if (model.exactState == null) exactLpStateKey(model) else null
    private var blockResults: List<FloatLpResult>? = null
    private var metrics = LpSolveMetrics()
    override var lastDegenerateColumns: Int = 0
        private set
    override var lastColumns: Int = 0
        private set
    var lastReservedConstructionWork: Long = 0L
        private set
    private var closed = false
    private var continuationTarget: Basis? = null
    override var recessionDirection: DoubleArray? = null
        private set
    override var solvedExactState: LpExactState? = null
        get() = field.takeIf { activeSolve == null }
        private set
    override var lastTermination: LpFloatTermination? = null
        private set

    override val lastMetrics: LpSolveMetrics get() = activeChild?.let { metrics + it.lastMetrics } ?: metrics
    override val lastPivots: Int get() = lastMetrics.pivots
    override val lastWorkOps: Long get() = lastMetrics.workOps
    override val lastRefactorizations: Int get() = lastMetrics.let {
        it.initialRefactorizations + it.warmStartRefactorizations + it.singularRecoveryRefactorizations +
            it.updateLimitRefactorizations + it.backendRequestedRefactorizations +
            it.reconcileRecoveryRefactorizations + it.numericalRecoveryRefactorizations + it.primalRefactorizations
    }
    override val currentWork: Long get() = metrics.workOps + when (val child = activeChild) {
        is ResumableLpSolver -> child.currentWork
        null -> 0L
        else -> child.lastMetrics.workOps
    }

    override fun deferUnscaledSourceDiagnostics() {
        for (solver in solvers) solver.deferUnscaledSourceDiagnostics()
    }

    override var infeasibleRay: DoubleArray? = null
        private set

    @Suppress("TooGenericExceptionCaught")
    override fun close() {
        if (closed) return
        closed = true
        val pending = activeSolve
        activeSolve = null
        var failure: Throwable? = null
        try {
            if (pending?.isAdvancing != true) pending?.close()
        } catch (cleanup: Throwable) {
            failure = cleanup
        }
        clearEvidence()
        reusable.fill(null)
        checkedBounds.fill(null)
        closeComponentSolvers(solvers, failure)
        failure?.let { throw it }
    }

    override fun solve(warm: Basis?): FloatLpResult? {
        check(activeSolve == null) { "LP solve is suspended" }
        solveStarted = true
        return immediateLpSolve { invoke { s -> s.solve(null) } }
    }

    override fun solvePrimal(warm: Basis?): FloatLpResult? {
        check(activeSolve == null) { "LP solve is suspended" }
        solveStarted = true
        return immediateLpSolve { invoke { s -> s.solvePrimal(null) } }
    }

    override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? {
        check(activeSolve == null) { "LP solve is suspended" }
        solveStarted = true
        return immediateLpSolve {
            invoke(allowance) { solver ->
                check(solver is RetainedLpSolver)
                solver.resolveBounds(remainingAllowance(allowance))
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun openSolve(allowance: LpFloatAllowance?, retainFundingStop: Boolean): LpSolveCursor<FloatLpResult?> {
        check(!closed && activeSolve == null) { "LP solve is closed or suspended" }
        return LpSolveCursor<FloatLpResult?>(completed = { activeSolve = null }) {
            val reuse = solveStarted
            solveStarted = true
            invoke(allowance) { child ->
                checkpoint(metrics.workOps)
                var started = false
                try {
                    if (child is ResumableLpSolver) {
                        child.openSolve(remainingAllowance(allowance), retainFundingStop).use { cursor ->
                            activeChild = child
                            started = true
                            var step = cursor.advance(shouldPause = { shouldPause(currentWork) })
                            while (step is LpSolveStep.Paused) {
                                pauseAt(currentWork)
                                step = cursor.advance(shouldPause = { shouldPause(currentWork) })
                            }
                            (step as LpSolveStep.Completed<FloatLpResult?>).value
                        }
                    } else {
                        activeChild = child
                        started = true
                        if (reuse) {
                            check(child is RetainedLpSolver)
                            child.resolveBounds(remainingAllowance(allowance))
                        } else {
                            child.solve(null)
                        }
                    }
                } catch (failure: Throwable) {
                    if (started) metrics += child.lastMetrics
                    throw failure
                } finally {
                    activeChild = null
                }
            }
        }.also { activeSolve = it }
    }

    @Suppress("TooGenericExceptionCaught") // Retire every child when adoption fails, including native failures.
    override fun adopt(state: LpExactState, token: Cancellation): Boolean {
        if (activeSolve != null) return false
        val previous = model.exactState ?: return false
        if (closed || !previous.sameMatrix(state) || token() || solvers.any { it !is RetainedLpSolver }) return false
        val nextModel = state.ownerWorkingModel(LpProjectionMeter(cancellation = token)) ?: return false
        val nextParts = ArrayList<LpNeighborhood>(parts.size)
        for (part in parts) nextParts += part.rebindExact(state, token) ?: return false
        if (token()) return false
        try {
            for (index in solvers.indices) {
                if (nextParts[index] === parts[index]) continue
                reusable[index] = null
                checkedBounds[index] = null
                if (!(solvers[index] as RetainedLpSolver).adopt(
                    checkNotNull(nextParts[index].model.exactState),
                    token,
                )) {
                    close()
                    return false
                }
            }
            if (token()) {
                close()
                return false
            }
        } catch (primary: Throwable) {
            if (!closed) {
                closed = true
                clearEvidence()
                reusable.fill(null)
                checkedBounds.fill(null)
                closeComponentSolvers(solvers, primary)
            }
            throw primary
        }
        model = nextModel
        parts = nextParts
        clearEvidence()
        return true
    }

    private fun clearEvidence() {
        blockResults = null
        solvedExactState = null
        continuationTarget = null
        recessionDirection = null
        infeasibleRay = null
        lastTermination = null
    }

    fun prepareLogicals(token: Cancellation, workLimit: Long): Basis? {
        metrics = LpSolveMetrics()
        lastDegenerateColumns = 0
        lastColumns = 0
        val stop = token or Cancellation { workLimit > 0L && currentWork >= workLimit }
        for (solver in solvers) {
            val persistent = solver as? PersistentLpSolver ?: return null
            activeChild = solver
            val basis = try {
                persistent.prepareLogicals(stop)
            } finally {
                activeChild = null
                metrics += persistent.lastMetrics
            }
            if (basis == null || stop()) return null
        }
        return componentBasis()
    }

    @Suppress("TooGenericExceptionCaught") // New children are private until the complete local edit can publish.
    fun appendLocalRows(
        state: LpExactState,
        token: Cancellation,
        workLimit: Long,
        engine: (LpModel, Cancellation, Long) -> PersistentLpSolver,
    ): Boolean {
        check(!closed && activeSolve == null)
        metrics = LpSolveMetrics()
        lastReservedConstructionWork = 0L
        val projection = LpProjectionMeter(
            workLimit = workLimit.takeIf { it > 0L } ?: Long.MAX_VALUE, cancellation = token,
        )
        val (working, nextParts) = try {
            localRowPlan(state, token, projection) ?: return false
        } catch (_: LpProjectionStop) {
            return false
        } finally {
            metrics += LpSolveMetrics(workOps = projection.work)
        }
        val changed = BooleanArray(parts.size) { nextParts[it] !== parts[it] }
        val replacements = ArrayList<LpSolver>()
        val nextSolvers = solvers.toMutableList()
        var published = false
        var failure: Throwable? = null
        try {
            val stop = token or Cancellation { workLimit > 0L && currentWork >= workLimit }
            for (index in parts.indices) {
                if (!changed[index]) continue
                if (stop()) return false
                val remaining = if (workLimit > 0L) workLimit - metrics.workOps else 0L
                val child = engine(nextParts[index].model, token, remaining)
                replacements += child
                nextSolvers[index] = child
                activeChild = child
                val basis = try {
                    child.prepareLogicals(stop)
                } finally {
                    activeChild = null
                    metrics += child.lastMetrics
                }
                if (basis == null || !basis.validFor(nextParts[index].model) || stop()) return false
            }
            if (token()) return false
            val retired = parts.indices.filter { changed[it] }.map { solvers[it] }
            model = working
            parts = nextParts
            solvers = nextSolvers
            for (index in parts.indices) if (changed[index]) {
                reusable[index] = null
                checkedBounds[index] = null
            }
            clearEvidence()
            published = true
            closeComponentSolvers(retired)
            return true
        } catch (primary: Throwable) {
            failure = primary
            if (published) {
                try { close() } catch (cleanup: Throwable) {
                    if (cleanup !== primary) primary.addSuppressed(cleanup)
                }
            }
            throw primary
        } finally {
            if (!published) closeComponentSolvers(replacements, failure)
        }
    }

    private fun localRowPlan(
        state: LpExactState,
        token: Cancellation,
        projection: LpProjectionMeter,
    ): Pair<LpModel, List<LpNeighborhood>>? {
        val previous = model.exactState ?: return null
        val source = state.model
        if (source.n != model.n || source.m <= model.m || token()) return null
        var entries = 0L
        for (column in 0 until model.n) {
            val values = source.columnEntries(column)
            entries += values.size
            projection.reserve(values.size.toLong() + 1L, values.size * 16L, matrix = true)
            if (values.filter { it.row < model.m } != previous.model.columnEntries(column)) return null
        }
        projection.reserve(source.m.toLong() + source.n, (source.m.toLong() + source.n) * 16L, matrix = true)
        if ((0 until model.m).any { previous.rows.row(it).id != state.rows.row(it).id }) return null
        val owner = IntArray(model.n) { -1 }
        for (index in parts.indices) for (column in parts[index].cols) owner[column] = index
        val added = Array(parts.size) { IntArrayList() }
        val working = state.ownerWorkingModel(projection) ?: return null
        projection.reserve(entries * 2L + source.m, entries * 4L + source.m * 8L, matrix = true)
        val rows = working.rowIndex()
        for (row in model.m until source.m) {
            projection.poll()
            val from = rows.rowPtr[row]
            val to = rows.rowPtr[row + 1]
            if (from == to) return null
            val block = owner[rows.colIdx[from]]
            if (block < 0 || (from until to).any { owner[rows.colIdx[it]] != block }) return null
            added[block].add(row)
        }
        val nextParts = ArrayList<LpNeighborhood>(parts.size)
        val scratch = IntArray(source.m) { -1 }
        for (index in parts.indices) {
            val part = parts[index]
            projection.reserve(part.cols.size.toLong() + part.rows.size, 0L, matrix = false)
            if (added[index].size == 0) {
                // Simultaneous edits to other blocks take the ordinary replacement route.
                if (part.rebindExact(state, token) !== part) return null
                nextParts += part
            } else {
                val columns = IntArrayList().apply { addAll(part.cols) }
                val selected = IntArrayList().apply {
                    addAll(part.rows)
                    added[index].forEach { add(it) }
                }
                val nonzeros = part.cols.sumOf { source.columnEntries(it).size.toLong() }
                projection.reserve(nonzeros * 8L + columns.size + selected.size,
                    nonzeros * 128L + (columns.size.toLong() + selected.size) * 128L, matrix = true)
                val next = working.restrictTo(columns, selected, null, copyCosts = true, scratch)
                val construction = LpScalingView.constructionWorkBound(next.model)
                projection.reserve(construction, 0L, matrix = true)
                lastReservedConstructionWork += construction
                nextParts += next
            }
        }
        return working to nextParts
    }

    @Suppress("TooGenericExceptionCaught") // Clear the invocation reason for any child failure.
    private suspend fun invoke(
        allowance: LpFloatAllowance? = null,
        op: suspend (LpSolver) -> FloatLpResult?,
    ): FloatLpResult? = try {
        stitch(allowance, op)
    } catch (primary: Throwable) {
        lastTermination = null
        blockResults = null
        solvedExactState = null
        continuationTarget = null
        recessionDirection = null
        reusable.fill(null)
        checkedBounds.fill(null)
        throw primary
    }

    private fun remainingAllowance(allowance: LpFloatAllowance?): LpFloatAllowance? = allowance?.copy(
        work = if (allowance.work > 0L) (allowance.work - metrics.workOps).coerceAtLeast(1L) else 0L,
        iterations = if (allowance.iterations > 0) (allowance.iterations - metrics.pivots).coerceAtLeast(1) else 0,
    )

    private suspend fun stitch(
        allowance: LpFloatAllowance?,
        op: suspend (LpSolver) -> FloatLpResult?,
    ): FloatLpResult? {
        check(!closed)
        lastTermination = null
        infeasibleRay = null
        blockResults = null
        continuationTarget = null
        recessionDirection = null
        metrics = LpSolveMetrics()
        lastDegenerateColumns = 0
        lastColumns = 0
        solvedExactState = model.exactState
        var objective = model.objConstantD
        val primal = DoubleArray(model.n)
        val duals = DoubleArray(model.m)
        val status = Array(model.numVars) { VarStatus.AT_LOWER }
        val basicVars = IntArray(model.m)
        var basicAt = 0
        var pivots = 0
        var maxFill = 0.0
        var maxDensity = 0.0
        var maxDim = 0
        val results = ArrayList<FloatLpResult>(parts.size)
        for (j in isolated) {
            val c = model.costD(j)
            val shifted = if (model.exactState == null) {
                if (c < 0.0) {
                    if (!model.hasFiniteUpper(j)) {
                        lastTermination = LpFloatTermination.UNBOUNDED_CANDIDATE
                        return null
                    }
                    model.upperD(j)
                } else {
                    0.0
                }
            } else {
                (isolatedValue(j) ?: run {
                    val direction = recessionDirection ?: DoubleArray(model.n).also { recessionDirection = it }
                    direction[j] = if (c < 0.0) 1.0 else -1.0
                    feasibleIsolatedValue(j)
                }).toDouble()
            }
            status[j] = when {
                model.fixed(j) -> VarStatus.FIXED
                c < 0.0 && model.hasFiniteUpper(j) -> VarStatus.AT_UPPER
                model.hasFiniteLower(j) && shifted == model.lowerD(j) -> VarStatus.AT_LOWER
                model.hasFiniteUpper(j) && shifted == model.upperD(j) -> VarStatus.AT_UPPER
                else -> VarStatus.FREE
            }
            primal[j] = shifted + model.loShiftD(j)
            objective += c * shifted
        }
        for (k in parts.indices) {
            val part = parts[k]
            val reused = reusable[k]?.takeIf { it.optimal && it.exactState === part.model.exactState &&
                solvers[k].solvedExactState === part.model.exactState }
            if (reused == null && allowance != null) {
                lastTermination = when {
                    allowance.work > 0L && metrics.workOps >= allowance.work -> LpFloatTermination.WORK
                    allowance.iterations > 0 && metrics.pivots >= allowance.iterations -> LpFloatTermination.PIVOTS
                    else -> null
                }
                if (lastTermination != null) return null
            }
            metrics += if (reused == null) LpSolveMetrics(componentSolves = 1) else LpSolveMetrics(componentReuses = 1)
            val r = (reused ?: op(solvers[k])) ?: run {
                metrics += solvers[k].lastMetrics
                lastTermination = solvers[k].lastTermination
                // A dual-unbounded block is a candidate infeasibility of the whole model: its float
                // ray extends with zeros on the other blocks' rows.
                solvers[k].infeasibleRay?.let { ray ->
                    val full = DoubleArray(model.m)
                    for (i in ray.indices) full[part.rows[i]] = ray[i]
                    infeasibleRay = full
                }
                solvers[k].recessionDirection?.let { direction ->
                    val full = DoubleArray(model.n)
                    for (column in direction.indices) full[part.cols[column]] = direction[column]
                    recessionDirection = full
                }
                if (recessionDirection != null) continuationTarget = componentBasis()
                return null
            }
            if (part.model.exactState != null &&
                (r.exactState !== part.model.exactState || solvers[k].solvedExactState !== part.model.exactState)
            ) {
                solvedExactState = null
                return null
            }
            if (reused == null) {
                metrics += solvers[k].lastMetrics
                lastDegenerateColumns += solvers[k].lastDegenerateColumns
                lastColumns += solvers[k].lastColumns
            }
            if (!r.optimal && lastTermination == null) lastTermination = solvers[k].lastTermination
            results.add(r)
            if (part.model.exactState != null && r.optimal) reusable[k] = r
            objective += r.objective - part.model.objConstantD
            val sub = part.model
            for (c in 0 until sub.n) {
                primal[part.cols[c]] = r.primal[c]
                status[part.cols[c]] = r.basis.status[c]
            }
            for (i in 0 until sub.m) {
                duals[part.rows[i]] = r.duals[i]
                status[model.slackCol(part.rows[i])] = r.basis.status[sub.n + i]
            }
            for (b in r.basis.basicVars) {
                basicVars[basicAt++] = if (b < sub.n) part.cols[b] else model.slackCol(part.rows[b - sub.n])
            }
            if (reused == null) pivots += r.pivots
            if (reused == null && r.luMaxFill > maxFill) maxFill = r.luMaxFill
            if (reused == null && r.luMaxDensity > maxDensity) {
                maxDensity = r.luMaxDensity
                maxDim = r.luMaxDim
            }
        }
        val basis = Basis(basicVars, status)
        // A float optimum can violate an exact source row; continuation must retain its full-model target.
        continuationTarget = basis
        if (recessionDirection != null) {
            lastTermination = LpFloatTermination.UNBOUNDED_CANDIDATE
            return null
        }
        blockResults = results
        if (results.all { it.optimal }) lastTermination = LpFloatTermination.OPTIMAL_CANDIDATE
        return FloatLpResult(
            basis = basis,
            objective = model.objectiveD(objective),
            duals = duals,
            primal = primal,
            pivots = pivots,
            luMaxFill = maxFill,
            luMaxDensity = maxDensity,
            luMaxDim = maxDim,
            blocks = parts.size,
            optimal = results.all { it.optimal },
            warmStarted = metrics.warmHits > 0,
            refactorizations = lastRefactorizations,
            exactState = model.exactState,
        )
    }

    override fun exactBound(observer: LpCertificationObserver?, policy: LpCertificationPolicy): CertifiedLpBound? {
        if (!currentAuthority()) return null
        val results = blockResults ?: return null
        var value = model.exactConstant()
        val citedRows = HashSet<Int>()
        val citedSides = ArrayList<LpExactCitedSide>()
        for (index in parts.indices) {
            val part = parts[index]
            val bound = checkedBounds[index]?.takeIf { it.policy === policy }?.bound
                ?: certifyLpBound(part.model, results[index].duals, observer, policy)?.also {
                    if (part.model.exactState != null) checkedBounds[index] = CheckedBound(policy, it)
                } ?: return null
            value += bound.value - part.model.exactConstant()
            model.exactState?.let { state ->
                val support = bound.support ?: return null
                for ((row, _) in support.rows) citedRows.add(part.rows[row])
                for (side in support.sides) {
                    val column = if (side.column < part.model.n) part.cols[side.column] else
                        model.slackCol(part.rows[side.column - part.model.n])
                    citedSides += side.copy(
                        column = column,
                        witness = state.activeSide(column, side.upper)?.takeIf { it.side == side.side }?.witness,
                    )
                }
            }
        }
        for (column in isolated) {
            val cost = model.exactCost(column)
            if (!cost.isZero) {
                val upper = cost.signum() < 0
                val side = if (upper) model.exactBounds(column).upper else model.exactBounds(column).lower
                if (side == null) return null
                value += cost * side.number.value
                model.exactState?.let { state ->
                    citedSides += LpExactCitedSide(column, upper, side, state.activeSide(column, upper)?.witness)
                }
            }
        }
        return CertifiedLpBound(
            model.sourceObjective(value),
            support = model.exactState?.let { state ->
                LpExactSupport(state, citedRows.sorted().map { it to state.model.row(it) }, citedSides)
            },
        )
    }

    override fun exactWitness(
        observer: LpCertificationObserver?,
        policy: LpCertificationPolicy,
        cancellation: Cancellation,
    ): ExactLpWitness? {
        if (!currentAuthority() || cancellation()) return null
        val results = blockResults ?: return null
        val point = MutableList(model.n) { model.exactShift(it) }
        for (index in parts.indices) {
            val part = parts[index]
            if (cancellation()) return null
            val exact = verifyExactBasis(
                part.model,
                results[index].basis,
                cache = solvers[index].exactBasisCache ?: ExactBasisCache(),
                cancellation = cancellation,
                observer = observer,
            )
            if (exact.singularRank != null) solvers[index].rejectSingularBasis(part.model, results[index].basis)
            val witness = policy.acceptNullable(
                LpCertifier.EXACT_BASIS,
                exact.witness,
            ) ?: policy.acceptNullable(
                LpCertifier.EXACT_POINT,
                exactPointWitness(part.model, results[index].primal, observer),
            ) ?: return null
            for (column in part.cols.indices) point[part.cols[column]] = witness.primal[column]
        }
        for (column in isolated) {
            point[column] += isolatedValue(column) ?: return null
        }
        return checkedLpWitness(model, point)
    }

    private fun currentAuthority(): Boolean = !closed && if (model.exactState != null) {
        solvedExactState === model.exactState
    } else {
        certificationKey?.let { exactLpStateKey(model)?.contentEquals(it) } == true
    }

    override fun gomoryCuts(maxCuts: Int): List<Cut> = localCuts(maxCuts) { owner, count -> owner.gomoryCuts(count) }

    override fun mirCuts(maxCuts: Int): List<Cut> = localCuts(maxCuts) { owner, count -> owner.mirCuts(count) }

    private fun localCuts(maxCuts: Int, separate: (TableauCutSolver, Int) -> List<Cut>): List<Cut> {
        if (!currentAuthority() || blockResults == null || maxCuts <= 0) return emptyList()
        val cuts = ArrayList<Cut>()
        for (index in parts.indices) {
            val owner = solvers[index] as? TableauCutSolver ?: continue
            val part = parts[index]
            for (cut in separate(owner, maxCuts - cuts.size)) {
                val proof = cut.tableau ?: continue
                if (proof.model !== part.model) continue
                cuts += Cut(
                    cut.cols.map { part.cols[it] }.toIntArray(), cut.coeffs, cut.rel, cut.rhs,
                    tableau = TableauCutProvenance(
                        model,
                        proof.columns.map { it.copy(column = part.cols[it.column]) },
                        proof.rows.map { row ->
                            CutInputRow(
                                part.rows[row.index], row.global, row.multiplier, row.rhs, row.relation,
                                row.columns.map { part.cols[it] }.toIntArray(), row.coefficients,
                                row.premises, row.exactPremises,
                            )
                        },
                        proof.divisor, proof.mir, proof.reduction,
                    ),
                )
                if (cuts.size == maxCuts) return cuts
            }
        }
        return cuts
    }

    private fun isolatedValue(column: Int): BigFraction? {
        val cost = model.exactCost(column)
        val bounds = model.exactBounds(column)
        if (!bounds.consistent) return null
        if (cost.signum() < 0) return bounds.upper?.number?.value
        if (cost.signum() > 0) return bounds.lower?.number?.value
        return feasibleIsolatedValue(column)
    }

    private fun feasibleIsolatedValue(column: Int): BigFraction {
        val bounds = model.exactBounds(column)
        val lower = bounds.lower
        val upper = bounds.upper
        if (lower != null && upper != null) {
            return (lower.number.value + upper.number.value) * BigFraction.ofLong(2L).reciprocal()
        }
        if (lower != null && (lower.number.value.signum() > 0 || lower.strict)) {
            return lower.number.value + if (lower.strict) BigFraction.ONE else BigFraction.ZERO
        }
        if (upper != null && (upper.number.value.signum() < 0 || upper.strict)) {
            return upper.number.value - if (upper.strict) BigFraction.ONE else BigFraction.ZERO
        }
        return BigFraction.ZERO
    }

    override fun continuationBasis(model: LpModel): Basis? {
        if (model.exactState !== this.model.exactState || !currentAuthority()) return null
        val basis = continuationTarget ?: return null
        return Basis(basis.basicVars.copyOf(), basis.status.copyOf(), captureEligible = false)
    }

    private fun componentBasis(): Basis {
        val status = Array(model.numVars) { column ->
            when {
                model.fixed(column) -> VarStatus.FIXED
                model.hasFiniteLower(column) -> VarStatus.AT_LOWER
                model.hasFiniteUpper(column) -> VarStatus.AT_UPPER
                else -> VarStatus.FREE
            }
        }
        val basic = IntArray(model.m)
        var at = 0
        for (index in parts.indices) {
            val part = parts[index]
            val sub = part.model
            val basis = solvers[index].continuationBasis(sub) ?: Basis(
                IntArray(sub.m) { sub.slackCol(it) },
                Array(sub.numVars) { if (it >= sub.n) VarStatus.BASIC else status[part.cols[it]] },
            )
            for (column in 0 until sub.numVars) {
                val parent = if (column < sub.n) part.cols[column] else model.slackCol(part.rows[column - sub.n])
                status[parent] = basis.status[column]
            }
            for (column in basis.basicVars) {
                basic[at++] = if (column < sub.n) part.cols[column] else model.slackCol(part.rows[column - sub.n])
            }
        }
        return Basis(basic, status, captureEligible = false)
    }
}

/**
 * Decompose [model] into its column components and wrap them as a [ComponentLpSolver], or null when
 * decomposition does not apply: fewer than two row-coupled blocks, or an empty structural row (its
 * feasibility is not attached to any column, so it belongs to no block — the monolithic engine
 * handles it). [engine] builds each block's solver — the same monolithic selection [newLpSolver]
 * uses, so a block solves exactly as the whole model would.
 */
internal fun componentLpSolverOrNull(
    model: LpModel,
    cancellation: Cancellation,
    engine: (LpModel, Cancellation) -> LpSolver,
): ComponentLpSolverCapability? = componentLpSolverOrNull(model, cancellation, engine, ::ComponentLpSolver)

@Suppress("TooGenericExceptionCaught") // Construction owns every child it created, including after native failures.
internal fun <T : ComponentLpSolverCapability> componentLpSolverOrNull(
    model: LpModel,
    cancellation: Cancellation,
    engine: (LpModel, Cancellation) -> LpSolver,
    component: (LpModel, List<LpNeighborhood>, List<LpSolver>, IntArray) -> T,
): T? {
    val n = model.n
    val m = model.m
    if (n == 0 || m < 2) return null
    val rowIndex = model.rowIndex()
    // Union-find over columns sharing a row.
    val parent = IntArray(n) { it }
    fun find(x: Int): Int {
        var r = x
        while (parent[r] != r) {
            parent[r] = parent[parent[r]]
            r = parent[r]
        }
        return r
    }
    val inRow = BooleanArray(n)
    for (i in 0 until m) {
        val from = rowIndex.rowPtr[i]
        val to = rowIndex.rowPtr[i + 1]
        if (from == to) return null // empty structural row → monolithic
        val root = find(rowIndex.colIdx[from])
        inRow[rowIndex.colIdx[from]] = true
        for (p in from + 1 until to) {
            val j = rowIndex.colIdx[p]
            inRow[j] = true
            val rj = find(j)
            if (rj != root) parent[rj] = root
        }
    }
    // Order components by first-seen root; a model with a single row-coupled block stays monolithic.
    val blockOf = IntArray(n) { -1 }
    var blocks = 0
    for (j in 0 until n) {
        if (!inRow[j]) continue
        val root = find(j)
        if (blockOf[root] < 0) blockOf[root] = blocks++
    }
    if (blocks < 2) return null

    val blockCols = Array(blocks) { IntArrayList() }
    for (j in 0 until n) if (inRow[j]) blockCols[blockOf[find(j)]].add(j)
    val blockRows = Array(blocks) { IntArrayList() }
    for (i in 0 until m) blockRows[blockOf[find(rowIndex.colIdx[rowIndex.rowPtr[i]])]].add(i)
    val isolated = IntArrayList()
    for (j in 0 until n) if (!inRow[j]) isolated.add(j)

    val parts = ArrayList<LpNeighborhood>(blocks)
    // One scratch buffer for the whole split: a fresh IntArray(m) per component makes the split alone
    // O(components x m), which on a model with many small components outweighs every restriction it
    // performs. Polled per component too - without it a long split never observes the deadline, since
    // the budget is only checked once a solver exists.
    val rowMapScratch = IntArray(model.m) { -1 }
    for (b in 0 until blocks) {
        if (cancellation()) return null
        parts.add(
            model.restrictTo(blockCols[b], blockRows[b], colMap = null, copyCosts = true, rowMapScratch),
        )
    }
    val solvers = ArrayList<LpSolver>(parts.size)
    try {
        for (part in parts) solvers += engine(part.model, cancellation)
        return component(model, parts, solvers, isolated.toIntArray())
    } catch (primary: Throwable) {
        closeComponentSolvers(solvers, primary)
        throw primary
    }
}

@Suppress("TooGenericExceptionCaught") // Release every child and preserve the original failure.
private fun closeComponentSolvers(solvers: List<LpSolver>, primary: Throwable? = null) {
    var failure = primary
    for (solver in solvers) {
        try {
            solver.close()
        } catch (closeFailure: Throwable) {
            if (failure == null) {
                failure = closeFailure
            } else if (failure !== closeFailure) {
                failure.addSuppressed(closeFailure)
            }
        }
    }
    if (primary == null && failure != null) throw failure
}
