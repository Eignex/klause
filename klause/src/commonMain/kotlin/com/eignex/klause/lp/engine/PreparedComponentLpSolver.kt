package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisOperationWork
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList

internal class PreparedComponentLayout(val parts: List<LpNeighborhood>, val isolated: IntArray)

internal fun newPreparedLpSolver(
    model: LpModel,
    cancellation: Cancellation = Cancellation.Never,
    refactorUpdateLimit: Int = DEFAULT_REFACTOR_UPDATE_LIMIT,
    iterationLimit: Int = 0,
    workLimit: Long = 0L,
    trackDegeneracy: Boolean = false,
    factory: LpEngineFactory = ProductionLpEngineFactory,
    pricing: LpPricingOptions = LpPricingOptions(),
    preparation: LpProjectionMeter = LpProjectionMeter(cancellation = cancellation),
): PreparedLpSolver? {
    val before = preparation.work
    val layout = preparedComponentLayout(model, preparation)
    val spent = preparation.work - before
    if (workLimit > 0L && spent >= workLimit) return null
    val remaining = if (workLimit > 0L) workLimit - spent else 0L
    if (layout == null) {
        return factory.newPersistentSolver(
            model, cancellation, refactorUpdateLimit, iterationLimit, remaining, trackDegeneracy, pricing,
        )
    }
    return preparedComponentLpSolver(model, layout, cancellation, remaining, iterationLimit) { child ->
        factory.newPersistentSolver(
            child, cancellation, refactorUpdateLimit, iterationLimit, remaining, trackDegeneracy, pricing,
        )
    }
}

internal fun preparedComponentLayout(model: LpModel, meter: LpProjectionMeter): PreparedComponentLayout? {
    if (model.exactState == null || model.m < 2) return null
    meter.reserve(model.n.toLong() * 4L + model.m * 3L + 1L, 0L, matrix = true)
    val parents = IntArray(model.n) { it }
    val first = IntArray(model.m) { -1 }
    val used = BooleanArray(model.n)
    fun root(column: Int): Int {
        var current = column
        while (parents[current] != current) {
            meter.reserve(1L, 0L, matrix = true)
            parents[current] = parents[parents[current]]
            current = parents[current]
        }
        return current
    }
    for (column in 0 until model.n) {
        meter.poll()
        model.forEachColumnRow(column) { row ->
            meter.reserve(1L, 0L, matrix = true)
            used[column] = true
            if (first[row] < 0) first[row] = column
            else {
                val left = root(first[row])
                val right = root(column)
                if (left != right) parents[right] = left
            }
        }
    }
    val block = IntArray(model.n) { -1 }
    val columns = ArrayList<IntArrayList>()
    val rows = ArrayList<IntArrayList>()
    val isolated = IntArrayList()
    for (column in 0 until model.n) {
        meter.poll()
        if (!used[column]) {
            isolated.add(column)
            continue
        }
        val key = root(column)
        if (block[key] < 0) {
            block[key] = columns.size
            columns.add(IntArrayList())
            rows.add(IntArrayList())
        }
        columns[block[key]].add(column)
    }
    var empty = -1
    for (row in 0 until model.m) {
        meter.poll()
        val owner = if (first[row] >= 0) block[root(first[row])] else {
            if (empty < 0) {
                empty = columns.size
                columns.add(IntArrayList())
                rows.add(IntArrayList())
            }
            empty
        }
        rows[owner].add(row)
    }
    if (columns.size < 2) return null
    val scratch = IntArray(model.m) { -1 }
    val parts = ArrayList<LpNeighborhood>(columns.size)
    for (index in columns.indices) {
        val units = columns[index].size.toLong() + rows[index].size * 2L
        meter.reserve(units * 16L + 64L, 0L, matrix = true)
        for (position in 0 until columns[index].size) {
            val column = columns[index][position]
            model.forEachColumnRow(column) { meter.reserve(8L, 0L, matrix = true) }
        }
        parts += model.restrictTo(columns[index], rows[index], null, true, scratch, meter)
    }
    return PreparedComponentLayout(parts, isolated.toIntArray())
}

@Suppress("TooGenericExceptionCaught")
internal fun preparedComponentLpSolver(
    model: LpModel,
    layout: PreparedComponentLayout,
    cancellation: Cancellation,
    workLimit: Long,
    iterationLimit: Int,
    engine: (LpModel) -> PersistentLpSolver,
): PreparedLpSolver {
    val children = ArrayList<PersistentLpSolver>(layout.parts.size)
    try {
        for (part in layout.parts) {
            if (cancellation()) throw LpProjectionStop()
            children += engine(part.model)
        }
        return PreparedComponentLpSolver(
            ComponentLpSolver(model, layout.parts, children, layout.isolated),
            children, cancellation, workLimit, iterationLimit,
        )
    } catch (primary: Throwable) {
        for (child in children) {
            try {
                child.close()
            } catch (cleanup: Throwable) {
                if (cleanup !== primary) primary.addSuppressed(cleanup)
            }
        }
        throw primary
    }
}

internal class PreparedComponentLpSolver(
    private val component: ComponentLpSolver,
    private val children: List<PersistentLpSolver>,
    private val cancellation: Cancellation,
    private val workLimit: Long,
    private val iterationLimit: Int,
) : PreparedLpSolver, ComponentLpSolverCapability by component {
    private var preparationMetrics: LpSolveMetrics? = null
    private var closed = false
    private var pendingAdoptionWork = 0L
    override var ownershipWork: Long = 0L
        private set
    override var lastIterations: Long = 0L
        private set
    override val lastMetrics: LpSolveMetrics get() = preparationMetrics ?: component.lastMetrics
    override val lastRefactorizations: Int get() = lastMetrics.let {
        it.initialRefactorizations + it.warmStartRefactorizations + it.singularRecoveryRefactorizations +
            it.updateLimitRefactorizations + it.backendRequestedRefactorizations +
            it.reconcileRecoveryRefactorizations + it.numericalRecoveryRefactorizations + it.primalRefactorizations
    }
    override val basisLifecycleWork: BasisOperationWork
        get() {
            var total = BasisOperationWork()
            for (child in children) {
                total = child.basisLifecycleWork?.let(total::mergedWith) ?: total.copy(complete = false)
            }
            return total
        }

    init {
        component.basisSlots = component.neighborhoods.map { it.rows.copyOf() }
    }

    override fun close() {
        if (closed) return
        closed = true
        component.close()
    }

    @Suppress("TooGenericExceptionCaught") // A failed child cannot remain published inside a composite owner.
    override fun adopt(state: LpExactState, token: Cancellation): Boolean {
        if (closed) return false
        val meter = LpProjectionMeter(
            workLimit = if (workLimit > 0L) workLimit else Long.MAX_VALUE,
            cancellation = token,
        )
        preparationMetrics = null
        return try {
            if (component.workingModel.exactState === state) {
                for ((index, part) in component.neighborhoods.withIndex()) {
                    meter.reserve(LpScalingView.constructionWorkBound(part.model), 0L, matrix = true)
                    if (!children[index].adopt(requireNotNull(part.model.exactState), token)) {
                        close()
                        return false
                    }
                }
                component.decline(LpFloatTermination.OTHER)
                true
            } else component.adoptWithMeter(state, token, meter)
        } catch (_: LpProjectionStop) {
            close()
            false
        } catch (primary: Throwable) {
            try {
                close()
            } catch (cleanup: Throwable) {
                if (cleanup !== primary) primary.addSuppressed(cleanup)
            }
            throw primary
        } finally {
            pendingAdoptionWork = meter.work
            ownershipWork += minOf(meter.work, Long.MAX_VALUE - ownershipWork)
        }
    }

    override fun prepareLogicals(token: Cancellation): Basis? = prepare(null, token)

    override fun prepareBasis(basis: Basis, token: Cancellation): Basis? = prepare(basis, token)

    private fun prepare(warm: Basis?, token: Cancellation): Basis? {
        if (closed) return null
        val model = component.workingModel
        val adoption = pendingAdoptionWork
        pendingAdoptionWork = 0L
        if (workLimit > 0L && adoption >= workLimit) return null
        val limit = if (workLimit > 0L) workLimit - adoption else 0L
        val mappingWork = model.numVars * 4L + model.m * 2L + 1L
        preparationMetrics = LpSolveMetrics()
        component.decline(LpFloatTermination.OTHER)
        if (token() || (limit > 0L && mappingWork >= limit) ||
            (warm != null && !warm.validFor(requireNotNull(model.exactState).model))) return null
        preparationMetrics = LpSolveMetrics(workOps = mappingWork)
        val parts = component.neighborhoods
        val localBases = ArrayList<Basis?>(parts.size)
        val slots = parts.map { IntArrayList() }
        val localBasic = parts.map { IntArrayList() }
        if (warm != null) {
            val owners = IntArray(model.numVars) { -1 }
            val locals = IntArray(model.numVars) { -1 }
            for ((index, part) in parts.withIndex()) {
                for (column in 0 until part.model.numVars) {
                    val parent = if (column < part.model.n) part.cols[column] else
                        model.slackCol(part.rows[column - part.model.n])
                    owners[parent] = index
                    locals[parent] = column
                }
            }
            for ((slot, basic) in warm.basicVars.withIndex()) {
                if (token()) return null
                val owner = owners[basic]
                if (owner < 0) return null
                slots[owner].add(slot)
                localBasic[owner].add(locals[basic])
            }
        }
        var spent = mappingWork
        for ((index, part) in parts.withIndex()) {
            if (token() || (limit > 0L && spent >= limit)) {
                component.decline(if (token()) LpFloatTermination.CANCELLED else LpFloatTermination.WORK)
                return null
            }
            if (warm == null) {
                for (row in part.rows) slots[index].add(row)
                localBases.add(null)
            } else {
                if (localBasic[index].size != part.model.m) return null
                val statuses = Array(part.model.numVars) { column ->
                    val parent = if (column < part.model.n) part.cols[column] else
                        model.slackCol(part.rows[column - part.model.n])
                    warm.status[parent]
                }
                localBases.add(Basis(localBasic[index].toIntArray(), statuses))
            }
            val child = children[index]
            val remaining = if (limit > 0L) limit - spent else Long.MAX_VALUE
            var preparing = true
            val stop = token or Cancellation { preparing && limit > 0L && child.lastWorkOps >= remaining }
            try {
                val basis = localBases[index]
                val prepared = if (basis == null) child.prepareLogicals(stop) else child.prepareBasis(basis, stop)
                if (prepared == null) return null
            } finally {
                preparing = false
                spent += minOf(child.lastWorkOps, Long.MAX_VALUE - spent)
                preparationMetrics = requireNotNull(preparationMetrics) + child.lastMetrics
            }
        }
        component.basisSlots = slots.map { it.toIntArray() }
        if (token()) return null
        return retainedBasis()
    }

    override fun retainedBasis(): Basis? {
        if (closed) return null
        val model = component.workingModel
        val status = Array(model.numVars) { column ->
            when {
                model.fixed(column) -> VarStatus.FIXED
                model.hasFiniteLower(column) -> VarStatus.AT_LOWER
                model.hasFiniteUpper(column) -> VarStatus.AT_UPPER
                else -> VarStatus.FREE
            }
        }
        val basic = IntArray(model.m)
        val slots = requireNotNull(component.basisSlots)
        for ((index, part) in component.neighborhoods.withIndex()) {
            val basis = children[index].retainedBasis() ?: return null
            for (column in basis.status.indices) {
                val parent = if (column < part.model.n) part.cols[column] else
                    model.slackCol(part.rows[column - part.model.n])
                status[parent] = basis.status[column]
            }
            for ((local, column) in basis.basicVars.withIndex()) {
                basic[slots[index][local]] = if (column < part.model.n) part.cols[column] else
                    model.slackCol(part.rows[column - part.model.n])
            }
        }
        return Basis(basic, status, captureEligible = false)
            .takeIf { it.validFor(requireNotNull(model.exactState).model) }
    }

    override fun solve(warm: Basis?): FloatLpResult? = solvePrepared(warm, false)

    override fun solvePrimal(warm: Basis?): FloatLpResult? = solvePrepared(warm, true)

    private fun solvePrepared(warm: Basis?, primal: Boolean): FloatLpResult? {
        val adoption = if (warm != null) pendingAdoptionWork else 0L
        val preparation = if (warm != null) {
            if (prepareBasis(warm, cancellation) == null) return null
            lastMetrics
        } else LpSolveMetrics()
        if (workLimit > 0L && (adoption >= workLimit || preparation.workOps >= workLimit - adoption)) {
            component.decline(LpFloatTermination.WORK)
            return null
        }
        val remaining = if (workLimit > 0L) workLimit - adoption - preparation.workOps else 0L
        val result = resolve(LpFloatAllowance(remaining, iterationLimit), primal)
        preparationMetrics = preparation + component.lastMetrics
        return result
    }

    override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? = resolve(allowance, false)

    private fun resolve(allowance: LpFloatAllowance?, primal: Boolean): FloatLpResult? {
        preparationMetrics = null
        lastIterations = 0L
        val model = component.workingModel
        val work = allowance?.work ?: workLimit
        val adoption = pendingAdoptionWork
        pendingAdoptionWork = 0L
        if (work > 0L && adoption >= work) {
            component.decline(LpFloatTermination.WORK)
            preparationMetrics = LpSolveMetrics()
            return null
        }
        val remainingWork = if (work > 0L) work - adoption else 0L
        val requestedIterations = allowance?.iterations ?: iterationLimit
        val iterations = if (requestedIterations > 0) requestedIterations.toLong() else
            minOf(Int.MAX_VALUE.toLong(), 50L * (model.m.toLong() + model.numVars) + 200L)
        var spent = 0L
        var stop: LpFloatTermination? = null
        val result = component.invokeComponents(admit = {
            if (cancellation() || (remainingWork > 0L && spent >= remainingWork) || lastIterations >= iterations) {
                stop = when {
                    cancellation() -> LpFloatTermination.CANCELLED
                    remainingWork > 0L && spent >= remainingWork -> LpFloatTermination.WORK
                    else -> LpFloatTermination.PIVOTS
                }
                false
            } else true
        }) { index, _ ->
                val child = children[index]
                val remaining = LpFloatAllowance(
                    if (remainingWork > 0L) remainingWork - spent else 0L,
                    (iterations - lastIterations).toInt(),
                )
                try {
                    if (primal) child.resolvePrimalBounds(remaining) else child.resolveBounds(remaining)
                } finally {
                    spent += minOf(child.lastWorkOps, Long.MAX_VALUE - spent)
                    lastIterations += minOf(child.lastIterations, Long.MAX_VALUE - lastIterations)
                }
        }
        stop?.let(component::decline)
        return result
    }
}
