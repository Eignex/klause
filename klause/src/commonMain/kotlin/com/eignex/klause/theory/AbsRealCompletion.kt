package com.eignex.klause.theory

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.RealAbs
import com.eignex.klause.factor.arithmetic.RealArrayMinMax
import com.eignex.klause.factor.arithmetic.RealFiniteDomain
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.linearRows
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolveCursor
import com.eignex.klause.lp.engine.LpSolveStep
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.ExactSearchRow
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchComponentSet
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchExactIntValue
import com.eignex.klause.solver.search.SearchExactRealValue
import com.eignex.klause.solver.search.SearchExecutionControlAdapter
import com.eignex.klause.solver.search.SearchExecutionControls
import com.eignex.klause.solver.search.SearchNodeDisposition
import com.eignex.klause.solver.search.SearchNodePolicy
import com.eignex.klause.solver.search.SearchRunEvent
import com.eignex.klause.solver.selectedFactorProjections
import com.eignex.klause.solver.validation.RealCompletionOptimization
import com.eignex.klause.solver.validation.ToleranceRealSearch
import com.eignex.klause.solver.validation.ToleranceSearchResult
import com.eignex.klause.theory.qflra.prepareExactLiraSource
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.counting
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction

// Completes the real coordinates of a fixed discrete point by an exact search over the linear encodings of the
// source's real relations. With a positive tolerance it searches the `ToleranceLinearLayout` model instead, in which
// every relation of the tolerance components holds within the slack its bound admits: a witness is a point within
// tolerance and a refutation shows that no such point exists.
internal class AbsRealCompletion(private val source: Problem, tolerance: SourceTolerance? = null) {
    private val encoders = source.selectedFactorProjections()
    private val layout = tolerance?.takeUnless { it.isExact }?.let { ToleranceLinearLayout(source, it) }

    // Whether the search within tolerance states every relation of the source.
    val searchesWithinTolerance: Boolean = layout?.searchable(encoders) == true

    // Whether a point of the search holds the relations only within the source tolerance.
    val readsTolerance: Boolean get() = layout != null

    // The number of real coordinates of the source, which every point of the search assigns.
    val realCount: Int get() = source.numRealVars

    sealed interface Result {
        // A completion; [withinTolerance] when it holds the relations only within the source tolerance.
        class Witness(val reals: List<BigFraction>, val withinTolerance: Boolean = false) : Result
        data object Refuted : Result
        data object Undecided : Result
    }

    class Completed(val result: Result, val work: Long)

    fun complete(
        sample: Sample,
        cancellation: ExecutionControl,
        controls: SearchExecutionControls? = null,
        solveContext: LpSolveContext = LpSolveContext.Production,
    ): Completed {
        var work = 0L
        val counted = cancellation.counting { units ->
            work = if (units > Long.MAX_VALUE - work) Long.MAX_VALUE else work + units
        }
        val encoding = encoding(sample, counted)
        val result = when (val found = search(sample, encoding, emptyList(), counted, controls, solveContext)) {
            is Result.Witness -> Result.Witness(found.reals, withinTolerance = layout != null)
            else -> found
        }
        return Completed(if (counted()) Result.Undecided else result, work)
    }

    // The minimization of `sum(costs(r) * x(r))` over the source reals at [sample]'s discrete coordinates, within the
    // source tolerance when this search reads it.
    fun optimize(
        sample: Sample,
        costs: List<BigFraction>,
        solveContext: LpSolveContext = LpSolveContext.Production,
    ): RealLeafOptimization = RealLeafOptimization(this, sample, costs, solveContext)

    // The completion model at [sample]'s discrete coordinates: every relation within the slack when this search reads
    // the tolerance, and exactly otherwise. Null when an encoder declines or [cancellation] stops the encoding.
    fun encoding(sample: Sample, cancellation: ExecutionControl): Problem? =
        layout?.model(encoders, sample, cancellation) ?: encoders.finiteLinearModel(sample, cancellation)
            .takeIf { layout == null }

    // A point of [encoding] at [sample]'s discrete coordinates under which [extra] holds as well.
    fun search(
        sample: Sample,
        encoding: Problem?,
        extra: List<Factor>,
        cancellation: ExecutionControl,
        controls: SearchExecutionControls? = null,
        solveContext: LpSolveContext = LpSolveContext.Production,
    ): Result = openSearch(sample, encoding, extra, cancellation,
        controls ?: SearchExecutionControlAdapter(cancellation), solveContext).use { cursor ->
        (cursor.advance() as LpSolveStep.Completed).value
    }

    fun openSearch(
        sample: Sample,
        encoding: Problem?,
        extra: List<Factor>,
        cancellation: ExecutionControl,
        controls: SearchExecutionControls = SearchExecutionControlAdapter(cancellation),
        solveContext: LpSolveContext = LpSolveContext.Production,
    ): LpSolveCursor<Result> = LpSolveCursor {
        var work = 0L
        val counted = cancellation.counting { units ->
            work = if (units > Long.MAX_VALUE - work) Long.MAX_VALUE else work + units
        }
        val metered = object : SearchExecutionControls by controls {
            override val cancellation: ExecutionControl = counted
        }
        val encoded = encoding ?: return@LpSolveCursor Result.Undecided
        val factors = ArrayList<Factor>()
        factors.addAll(encoded.factors)
        factors.addAll(extra)
        for (id in 0 until source.numBoolVars) {
            if (counted()) return@LpSolveCursor Result.Undecided
            counted.charge(1)
            factors += Clause(intArrayOf(Lit.make(id, sample.boolValue(id))))
        }
        for (id in 0 until source.numIntVars) {
            if (counted()) return@LpSolveCursor Result.Undecided
            counted.charge(1)
            factors += ExactSearchRow.of(intArrayOf(Term.ofIntVar(id)), listOf(BigFraction.ONE), LinearOp.EQ,
                BigFraction.of(sample.exactIntValue(id), BIG_ONE))
        }
        val model = encoded.rebindEncoding(encoded, factors.toTypedArray(), null)
        val factory = prepareExactLiraSource(model, counted) ?: return@LpSolveCursor Result.Undecided
        factory.open { assignment, values ->
            assignment.ints.forEachIndexed { id, value ->
                values.contribute(SearchExactIntValue(SourceIntId(id)), value)
            }
            assignment.reals.forEachIndexed { id, value ->
                values.contribute(SearchExactRealValue(SourceRealId(id)), value)
            }
        }.use { component ->
            component.solveWith(solveContext)
            val session = SearchComponentSet(listOf(component)).session(cancellation = counted,
                atoms = SearchAtomRegistry(model.numBoolVars), executionControls = metered)
            when (session.initialize()) {
                is ComponentResult.Conflict -> Result.Refuted
                ComponentResult.Indeterminate -> Result.Undecided
                ComponentResult.Consistent -> {
                    val run = session.openRun(model.numBoolVars, nodePolicy = object : SearchNodePolicy {
                        override fun beforeBranch(context: SearchContext): SearchNodeDisposition =
                            if (shouldPause(work)) SearchNodeDisposition.Pause else SearchNodeDisposition.Expand
                    })
                    run.pauseBeforeDecision = { shouldPause(work) }
                    var result = run.next()
                    while (result == SearchRunEvent.Paused) {
                        pauseAt(work)
                        if (counted()) return@LpSolveCursor Result.Undecided
                        result = run.next()
                    }
                    when (val found = result) {
                        SearchRunEvent.Exhausted -> Result.Refuted
                        is SearchRunEvent.Indeterminate, SearchRunEvent.Paused -> Result.Undecided
                        is SearchRunEvent.Satisfied -> {
                            val reals = List(source.numRealVars) { id ->
                                found.model.valueOf(SearchExactRealValue(SourceRealId(id)))
                                    ?: return@LpSolveCursor Result.Undecided
                            }
                            if (counted()) Result.Undecided else Result.Witness(reals)
                        }
                    }
                }
            }
        }
    }
}

// The tolerance search of a source, read through [lift] from the frame its callers complete in.
// Built on first use and shared by every worker of an execution: the completion holds only immutable state, and each
// search encodes and solves its own model.
internal class SourceToleranceSearch private constructor(
    private val completion: Lazy<AbsRealCompletion?>,
    private val realCount: Int,
    private val lift: (Sample) -> Sample,
) : ToleranceRealSearch {
    constructor(source: Problem, policy: SourceTolerance) : this(
        lazy { AbsRealCompletion(source, policy).takeIf { it.searchesWithinTolerance } }, source.numRealVars, { it },
    )

    override fun complete(
        discrete: Sample,
        cancellation: ExecutionControl,
        controls: SearchExecutionControls?,
        context: LpSolveContext,
    ): ToleranceSearchResult {
        val search = completion.value ?: return ToleranceSearchResult.Unavailable
        val point = lift(discrete)
        require(point.numRealVars == 0 || point.numRealVars == realCount) { "leaf frame differs from its source" }
        val completed = search.complete(point, cancellation, controls, context)
        return when (val result = completed.result) {
            is AbsRealCompletion.Result.Witness -> if (result.reals.size == realCount) {
                ToleranceSearchResult.Witness(result.reals, completed.work)
            } else ToleranceSearchResult.Undecided(completed.work)
            AbsRealCompletion.Result.Refuted -> ToleranceSearchResult.Refuted(completed.work)
            AbsRealCompletion.Result.Undecided -> ToleranceSearchResult.Undecided(completed.work)
        }
    }

    override fun optimize(
        discrete: Sample,
        costs: List<BigFraction>,
        context: LpSolveContext,
    ): RealCompletionOptimization? {
        // Real coordinates keep their indices across the lift, so costs over the working reals read the source's; a
        // frame whose real count differs has no such reading and leaves the optimization unavailable.
        if (costs.size != realCount) return null
        val search = completion.value ?: return null
        val point = lift(discrete)
        if (point.numRealVars != 0 && point.numRealVars != realCount) return null
        return search.optimize(point, costs, context)
    }

    override fun reconstructedBy(lift: (Sample) -> Sample): ToleranceRealSearch =
        SourceToleranceSearch(completion, realCount) { this.lift(lift(it)) }
}

// Whether a fixed discrete point needs a search to complete the reals: an absolute value, a finite membership, a
// leaf-enforced extremum or a product with unguarded finite choices states a disjunction that no single linear
// relaxation decides.
internal fun Problem.requiresRealCompletionSearch(): Boolean = factors.any {
    it is RealAbs || it is RealFiniteDomain || (it is RealArrayMinMax && it.leafEnforced) ||
        (it is ContinuousProduct && it.finiteChoices != null && !it.finiteChoices.hasGuards)
}

// Whether a fixed discrete point's reals can have an optimum off the leaf LP: a relation the completion search needs,
// or a disjunction, disequality or strict row over a real, whose sides the LP states as one closed region.
internal fun Problem.statesRealChoices(): Boolean = requiresRealCompletionSearch() || factors.any { factor ->
    factor.variables.reals.isNotEmpty() && (factor.linearForm is LinearForm.Disjunction ||
        factor.linearRows.any { it.strict || it.relation == LinearOp.NE })
}
