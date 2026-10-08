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
 * reported as a failed solve exactly like the monolithic engine would.
 *
 * The warm-start handle is not split across blocks — a decomposed solve cold-starts each block
 * (small by construction, which is the point); the warm contract permits this (it changes only the
 * pivot path, never the result).
 */
internal class ComponentLpSolver(
    private var model: LpModel,
    private var parts: List<LpNeighborhood>,
    private val solvers: List<LpSolver>,
    private val isolated: IntArray,
) : RetainedComponentLpSolverCapability {
    private val certificationKey = if (model.exactState == null) exactLpStateKey(model) else null
    private var blockResults: List<FloatLpResult>? = null
    private var metrics = LpSolveMetrics()
    private var closed = false
    private var stoppedBasis: Basis? = null
    override var recessionDirection: DoubleArray? = null
        private set
    override var solvedExactState: LpExactState? = null
        private set
    override var lastTermination: LpFloatTermination? = null
        private set

    override val lastMetrics: LpSolveMetrics get() = metrics

    override var infeasibleRay: DoubleArray? = null
        private set

    override fun close() {
        if (closed) return
        closed = true
        clearEvidence()
        closeComponentSolvers(solvers)
    }

    override fun solve(warm: Basis?): FloatLpResult? = invoke { s -> s.solve(null) }

    override fun solvePrimal(warm: Basis?): FloatLpResult? = invoke { s -> s.solvePrimal(null) }

    override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? = invoke { solver ->
        check(solver is RetainedLpSolver)
        solver.resolveBounds(allowance)
    }

    override fun adopt(state: LpExactState, token: Cancellation): Boolean {
        val previous = model.exactState ?: return false
        if (closed || !previous.sameMatrix(state) || token() || solvers.any { it !is RetainedLpSolver }) return false
        val nextModel = state.ownerWorkingModel(LpProjectionMeter(cancellation = token)) ?: return false
        val nextParts = ArrayList<LpNeighborhood>(parts.size)
        for (part in parts) nextParts += part.rebindExact(state, token) ?: return false
        if (token()) return false
        try {
            for (index in solvers.indices) {
                if (!(solvers[index] as RetainedLpSolver).adopt(checkNotNull(nextParts[index].model.exactState), token)) {
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
        stoppedBasis = null
        recessionDirection = null
        infeasibleRay = null
        lastTermination = null
    }

    @Suppress("TooGenericExceptionCaught") // Clear the invocation reason for any child failure.
    private inline fun invoke(op: (LpSolver) -> FloatLpResult?): FloatLpResult? = try {
        stitch(op)
    } catch (primary: Throwable) {
        lastTermination = null
        blockResults = null
        solvedExactState = null
        stoppedBasis = null
        recessionDirection = null
        throw primary
    }

    private inline fun stitch(op: (LpSolver) -> FloatLpResult?): FloatLpResult? {
        check(!closed)
        lastTermination = null
        infeasibleRay = null
        blockResults = null
        stoppedBasis = null
        recessionDirection = null
        metrics = LpSolveMetrics()
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
            val r = op(solvers[k]) ?: run {
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
                if (recessionDirection != null) stoppedBasis = componentBasis()
                return null
            }
            if (part.model.exactState != null &&
                (r.exactState !== part.model.exactState || solvers[k].solvedExactState !== part.model.exactState)
            ) {
                solvedExactState = null
                return null
            }
            metrics += solvers[k].lastMetrics
            if (!r.optimal && lastTermination == null) lastTermination = solvers[k].lastTermination
            results.add(r)
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
            pivots += r.pivots
            if (r.luMaxFill > maxFill) maxFill = r.luMaxFill
            if (r.luMaxDensity > maxDensity) {
                maxDensity = r.luMaxDensity
                maxDim = r.luMaxDim
            }
        }
        val basis = Basis(basicVars, status)
        if (recessionDirection != null) {
            lastTermination = LpFloatTermination.UNBOUNDED_CANDIDATE
            stoppedBasis = basis
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
            warmStarted = results.any { it.warmStarted },
            refactorizations = results.sumOf { it.refactorizations },
            exactState = solvedExactState,
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
            val bound = certifyLpBound(part.model, results[index].duals, observer, policy) ?: return null
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
        val basis = stoppedBasis ?: return null
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
            if (failure == null) failure = closeFailure else if (failure !== closeFailure) failure.addSuppressed(closeFailure)
        }
    }
    if (primary == null && failure != null) throw failure
}
