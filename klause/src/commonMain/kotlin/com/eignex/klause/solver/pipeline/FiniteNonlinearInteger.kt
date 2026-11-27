package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.arithmetic.polynomial.PolynomialResource
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.DeducedRestrictions
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchModel
import com.eignex.klause.solver.search.completedSearchSample
import com.eignex.klause.theory.qfnia.NonlinearIntegerCursor
import com.eignex.klause.theory.qfnia.NonlinearIntegerParams
import com.eignex.klause.theory.qfnia.NonlinearIntegerRootStore
import com.eignex.klause.theory.qfnia.NonlinearIntegerStrategy
import com.eignex.klause.theory.qfnra.PolynomialCondition
import com.eignex.klause.theory.qfnra.PolynomialSearchBounds
import com.eignex.klause.theory.qfnra.PolynomialTheoryService
import com.eignex.klause.theory.qfnra.QfNraAssignment
import com.eignex.klause.theory.qfnra.QfNraParams
import com.eignex.klause.theory.qfnra.QfNraResult
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.fitsLong
import com.eignex.util.numbers.toLongExact

internal fun executeFiniteNonlinearInteger(
    request: NonlinearIntegerTheoryRequest,
    projection: FactorPolynomialProjection,
    params: NonlinearIntegerTheoryParams,
    engine: FiniteEngine,
): PolynomialTheoryExecution {
    val plan = request.preparedPlan
    fun unknown(reason: TerminationReason, stats: PolynomialTheoryStats = PolynomialTheoryStats()) =
        PolynomialTheoryExecution(OpenTheoryResult.Unknown(reason, SolveStats.EMPTY), stats)
    if (!plan.capabilities.admission(engine).runnable) return unknown(TerminationReason.Unsupported)
    val polynomial = params.polynomial
    val backtrack = polynomial.strategy.backtrack.withAssumptions(polynomial.strategy.assumptions)
    val scope = QuadraticNativeScope(polynomial, concurrent = false)
    val stop = backtrack.cancellation.withAccounting(scope.polynomial) or scope.polynomial
    fun stopReason(): TerminationReason = when {
        polynomial.timeout() || backtrack.cancellation.deadline()?.hasPassedNow() == true -> TerminationReason.Timeout
        backtrack.cancellation() -> TerminationReason.Cancelled
        else -> scope.reason()
    }
    val budget = params.nodeBudget ?: backtrack.nodeBudget ?: polynomial.strategy.localSearch.nodeBudget
    val candidateLimit = minOf(params.maximumCandidates, backtrack.maxDecisions,
        backtrack.maxInstructions ?: Long.MAX_VALUE)
    if (stop() || candidateLimit == 0L || budget?.exhausted() == true) return unknown(stopReason())
    val traversalStop = stop or ExecutionControl { budget?.exhausted() == true }

    val ownership = requireNotNull(projection.finiteOwnership)
    val cp = ownership.cpProjection(projection.model, emptyMap(), stop)
        ?: return unknown(stopReason(), scope.polynomialStats)
    val assumptions = backtrack.assumptions
    val finiteAssumptions = assumptions.forFiniteNonlinearPeer(cp)
    val fixed = buildMap {
        assumptions.forEachInt { id, value ->
            require(id in 0 until projection.model.numIntVars) { "nonlinear integer assumption id out of range" }
            if (cp.cpId(id) < 0) put(plan.integerCoordinate(id), BigFraction.ofLong(value))
        }
    }
    val work = FiniteNonlinearIntegerWork(candidateLimit, budget)
    val solved = BacktrackSolver(cp.problem).solve(backtrack.copy(
        assumptions = finiteAssumptions,
        cancellation = traversalStop,
        nodeBudget = budget,
        // Imported source evidence uses a different coordinate frame and assumption scope.
        clauseExchange = null,
        cutExchange = null,
        globalVarBoundSink = null,
        globalVarLowerSupplier = null,
        globalVarUpperSupplier = null,
        globalVarImportSink = null,
        pooledIncumbents = null,
        componentFactory = {
            listOf(FiniteNonlinearIntegerComponent(
                projection, cp, fixed, params, scope, stop, work,
            ))
        },
    ))
    val verdict = when {
        stop() -> OpenTheoryResult.Unknown(stopReason(), solved.stats)
        solved is SolveResult.Sat -> OpenTheoryResult.Sat(nonlinearIntegerAssignment(solved.assignment), solved.stats)
        solved is SolveResult.Unsat && projection.refutesSource -> OpenTheoryResult.Unsat(solved.stats)
        else -> OpenTheoryResult.Unknown(when {
            work.nodesExhausted -> TerminationReason.BudgetExhausted
            work.resource != null -> TerminationReason.RepresentationDeclined
            work.incomplete -> TerminationReason.BudgetExhausted
            solved is SolveResult.Unknown -> solved.reason
            else -> TerminationReason.RepresentationDeclined
        }, solved.stats)
    }
    if (work.resource != null && verdict is OpenTheoryResult.Unknown) scope.decline(requireNotNull(work.resource))
    return PolynomialTheoryExecution(verdict, scope.polynomialStats, scope.resource)
}

private class FiniteNonlinearIntegerWork(
    private var candidatesLeft: Long,
    private val nodeBudget: NodeBudget?,
) {
    val remaining: Long get() = minOf(candidatesLeft, nodeBudget?.decisionsLeft() ?: Long.MAX_VALUE)
    val nodesExhausted: Boolean get() = nodeBudget?.exhausted() == true
    var incomplete = false
    var resource: PolynomialResource? = null

    fun spend(candidates: Long) {
        candidatesLeft -= candidates
        val budget = nodeBudget ?: return
        var remaining = candidates
        while (remaining > 0) {
            budget.spend()
            remaining--
        }
    }
}

private class FiniteNonlinearIntegerComponent(
    private val projection: FactorPolynomialProjection,
    private val cp: CpProblemProjection,
    fixed: Map<Int, BigFraction>,
    private val params: NonlinearIntegerTheoryParams,
    private val scope: QuadraticNativeScope,
    private val stop: ExecutionControl,
    private val work: FiniteNonlinearIntegerWork,
) : SearchComponent, AutoCloseable {
    private val source = projection.formula
    private val openColumns = requireNotNull(projection.finiteOwnership).theoryIntVars
        .map { source.integerCoordinates[it] }
    private val shared = projection.attributions.flatMap { it.integerEmbeddings.entries }
        .filter { cp.cpId(it.key.index) >= 0 }
        .associate { (id, column) -> cp.cpId(id.index) to column.index }
    private val arithmetic = QfNraParams(stop, polynomialLimits = PolynomialLimits(
        params.polynomial.maximumDenseCoefficients, params.polynomial.maximumMatrixCells,
    ), cellRefinement = params.cellRefinement)
    private val invocation = PolynomialTheoryService(source.formula, source.boolCount, source.numericCount)
        .invocation(arithmetic)
    private val bounds = PolynomialSearchBounds(source.boolCount, invocation, shared, fixed, checkFeasibility = true)
    private val roots = NonlinearIntegerRootStore(source)
    private var witness: Sample? = null

    override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
        invalidate()
        return ComponentResult.Consistent
    }

    override fun retract(decisionLevel: Int) = invalidate()

    private fun invalidate() {
        witness = null
        bounds.invalidate()
    }

    override fun propagate(context: SearchContext): ComponentResult =
        bounds.tighten(context, workAllowance = remainingNativeWork()?.div(4))

    override fun check(context: SearchContext): ComponentCheck {
        if (witness != null) return ComponentCheck.Feasible
        val signature = bounds.signature(context)
        check(signature.lower == signature.upper && signature.bools.size == source.boolCount)
        val condition = bounds.condition(signature)
        if (work.nodesExhausted || context.cancelled() || !context.consumeCheck()) {
            work.incomplete = true
            return ComponentCheck.Indeterminate
        }
        return complete(context, condition)
    }

    private fun complete(context: SearchContext, condition: PolynomialCondition): ComponentCheck {
        val queryStop = stop or ExecutionControl { context.cancelled() }
        val cursorParams = NonlinearIntegerParams(
            arithmetic.copy(cancellation = queryStop, maximumWork = remainingNativeWork()),
            work.remaining,
            NonlinearIntegerStrategy.SEARCH,
            params.polynomial.strategy.backtrack.randomSeed ?: 1L,
            params.reuseSourceCells, params.steerSourceCells, params.admitIntegerRoots, params.residueRefutation,
        )
        val completed = NonlinearIntegerCursor(source, cursorParams, condition, roots, openColumns,
            relaxWithoutCandidates = true).use {
            requireNotNull(it.runSlice())
        }
        scope.observe(completed.stats.polynomial)
        work.spend(completed.stats.candidates)
        return when (val result = completed.result) {
            is QfNraResult.Satisfiable -> {
                witness = capture(result.assignment, context)
                ComponentCheck.Feasible
            }
            is QfNraResult.Unsatisfiable -> ComponentCheck.Infeasible()
            is QfNraResult.Unknown -> {
                work.incomplete = true
                if (work.resource == null) work.resource = result.resource
                ComponentCheck.Indeterminate
            }
        }
    }

    private fun remainingNativeWork(): Long? =
        params.polynomial.maximumWork?.let { (it - scope.work).coerceAtLeast(0L) }

    private fun capture(assignment: QfNraAssignment, context: SearchContext): Sample {
        val ints = source.integerCoordinates.mapIndexed { id, column ->
            val local = cp.cpId(id)
            if (local < 0) requireNotNull(assignment.realValue(column).rational).num
            else bigIntOf(requireNotNull(context.intLowerBound(local)))
        }
        val reals = source.realCoordinates.map(assignment::realValue)
        val longs = if (ints.all { it.fitsLong() }) LongArray(ints.size) { ints[it].toLongExact() }
        else longArrayOf()
        return Sample(assignment.bools, longs, DoubleArray(reals.size) { reals[it].guideDouble },
            exactInts = ints, algebraicReals = reals)
    }

    override fun contributeModel(model: SearchModel, context: SearchContext) {
        model.contribute(completedSearchSample, requireNotNull(witness))
    }

    override fun close() {
        scope.observe(invocation.stats)
        invocation.close()
    }
}

private fun Assumptions.forFiniteNonlinearPeer(cp: CpProblemProjection): Assumptions {
    val finite = intKeys.indices.filter { cp.cpId(intKeys[it]) >= 0 }
    val restrictions = deductions
    fun local(ids: IntArray): IntArray = IntArray(ids.size) {
        cp.cpId(ids[it]).also { id -> require(id >= 0) { "open nonlinear restrictions require explicit pins" } }
    }
    return Assumptions(boolKeys, boolValues, IntArray(finite.size) { cp.cpId(intKeys[finite[it]]) },
        LongArray(finite.size) { intValues[finite[it]] },
        DeducedRestrictions(local(restrictions.intMinKeys), restrictions.intMinValues,
            local(restrictions.intMaxKeys), restrictions.intMaxValues, local(restrictions.intHoleVarIds),
            restrictions.intHoleValues, local(restrictions.intSetKeys), restrictions.intSetOffsets,
            restrictions.intSetValues))
}
