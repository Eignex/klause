package com.eignex.klause.theory

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.RealAbs
import com.eignex.klause.factor.arithmetic.RealFiniteDomain
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.ir.Term
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.ExactSearchRow
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchComponentSet
import com.eignex.klause.solver.search.SearchExactIntValue
import com.eignex.klause.solver.search.SearchExactRealValue
import com.eignex.klause.solver.search.SearchExecutionControlAdapter
import com.eignex.klause.solver.search.SearchExecutionControls
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.solver.selectedFactorProjections
import com.eignex.klause.solver.validation.ToleranceRealSearch
import com.eignex.klause.solver.validation.ToleranceSearchResult
import com.eignex.klause.theory.qflra.prepareExactLiraSource
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.util.numbers.BIG_ONE

// Completes the real coordinates of a fixed discrete point by an exact search over the linear encodings of the
// source's real relations. With a positive tolerance it searches the `ToleranceLinearLayout` model instead, in which
// every relation of the tolerance components holds within the slack its bound admits: a witness is a point within
// tolerance and a refutation shows that no such point exists.
internal class AbsRealCompletion(private val source: Problem, tolerance: SourceTolerance? = null) {
    private val encoders = source.selectedFactorProjections()
    private val layout = tolerance?.takeUnless { it.isExact }?.let { ToleranceLinearLayout(source, it) }

    sealed interface Result {
        // A completion; [withinTolerance] when it holds the relations only within the source tolerance.
        class Witness(val reals: List<BigFraction>, val withinTolerance: Boolean = false) : Result
        data object Refuted : Result
        data object Undecided : Result
    }

    class Completed(val result: Result, val work: Long)

    fun complete(
        sample: Sample,
        cancellation: Cancellation,
        controls: SearchExecutionControls? = null,
        solveContext: LpSolveContext = LpSolveContext.Production,
    ): Completed {
        var work = 0L
        val counted = object : Cancellation {
            override fun isCancelled(): Boolean = cancellation()
            override fun deadline() = cancellation.deadline()
            override fun workMeter(): WorkMeter = WorkMeter { units ->
                work = if (units > Long.MAX_VALUE - work) Long.MAX_VALUE else work + units
                cancellation.charge(units)
            }
        }
        val containing = controls ?: SearchExecutionControlAdapter(cancellation)
        val metered = object : SearchExecutionControls by containing {
            override val cancellation: Cancellation = counted
        }
        val encoding = layout?.model(encoders, sample, counted) ?: encoders.finiteLinearModel(sample, counted)
            .takeIf { layout == null }
        val result = when (val found = completeWith(sample, counted, metered, solveContext, encoding)) {
            is Result.Witness -> Result.Witness(found.reals, withinTolerance = layout != null)
            else -> found
        }
        return Completed(if (counted()) Result.Undecided else result, work)
    }

    private fun completeWith(
        sample: Sample,
        cancellation: Cancellation,
        controls: SearchExecutionControls,
        solveContext: LpSolveContext,
        encoding: Problem?,
    ): Result {
        val encoded = encoding ?: return Result.Undecided
        val factors = ArrayList<Factor>()
        factors.addAll(encoded.factors)
        for (id in 0 until source.numBoolVars) {
            if (cancellation()) return Result.Undecided
            cancellation.charge(1)
            factors += Clause(intArrayOf(Lit.make(id, sample.boolValue(id))))
        }
        for (id in 0 until source.numIntVars) {
            if (cancellation()) return Result.Undecided
            cancellation.charge(1)
            factors += ExactSearchRow.of(intArrayOf(Term.ofIntVar(id)), listOf(BigFraction.ONE), LinearOp.EQ,
                BigFraction.of(sample.exactIntValue(id), BIG_ONE))
        }
        val model = encoded.rebindEncoding(encoded, factors.toTypedArray(), null)
        val factory = prepareExactLiraSource(model, cancellation) ?: return Result.Undecided
        factory.open { assignment, values ->
            assignment.ints.forEachIndexed { id, value ->
                values.contribute(SearchExactIntValue(SourceIntId(id)), value)
            }
            assignment.reals.forEachIndexed { id, value ->
                values.contribute(SearchExactRealValue(SourceRealId(id)), value)
            }
        }.use { component ->
            component.solveWith(solveContext)
            val session = SearchComponentSet(listOf(component)).session(cancellation = cancellation,
                atoms = SearchAtomRegistry(model.numBoolVars), executionControls = controls)
            return when (session.initialize()) {
                is ComponentResult.Conflict -> Result.Refuted
                ComponentResult.Indeterminate -> Result.Undecided
                ComponentResult.Consistent -> when (val result = session.solve(model.numBoolVars)) {
                    SearchResult.Exhausted -> Result.Refuted
                    SearchResult.Indeterminate -> Result.Undecided
                    is SearchResult.Satisfied -> {
                        val reals = List(source.numRealVars) { id ->
                            result.model.valueOf(SearchExactRealValue(SourceRealId(id))) ?: return Result.Undecided
                        }
                        if (cancellation()) Result.Undecided else Result.Witness(reals)
                    }
                }
            }
        }
    }
}

// The tolerance search of a source, read through [lift] from the frame its callers complete in.
internal class SourceToleranceSearch private constructor(
    private val completion: AbsRealCompletion,
    private val realCount: Int,
    private val lift: (Sample) -> Sample,
) : ToleranceRealSearch {
    constructor(source: Problem, policy: SourceTolerance) :
        this(AbsRealCompletion(source, policy), source.numRealVars, { it })

    override fun complete(
        discrete: Sample,
        cancellation: Cancellation,
        controls: SearchExecutionControls?,
        context: LpSolveContext,
    ): ToleranceSearchResult {
        val point = lift(discrete)
        val completed = completion.complete(point, cancellation, controls, context)
        return when (val result = completed.result) {
            is AbsRealCompletion.Result.Witness -> if (result.reals.size == realCount) {
                ToleranceSearchResult.Witness(result.reals, completed.work)
            } else ToleranceSearchResult.Undecided(completed.work)
            AbsRealCompletion.Result.Refuted -> ToleranceSearchResult.Refuted(completed.work)
            AbsRealCompletion.Result.Undecided -> ToleranceSearchResult.Undecided(completed.work)
        }
    }

    override fun reconstructedBy(lift: (Sample) -> Sample): ToleranceRealSearch =
        SourceToleranceSearch(completion, realCount) { this.lift(lift(it)) }
}

// Whether a fixed discrete point needs a search to complete the reals: an absolute value, a finite membership or a
// product with unguarded finite choices states a disjunction that no single linear relaxation decides.
internal fun Problem.requiresRealCompletionSearch(): Boolean = factors.any {
    it is RealAbs || it is RealFiniteDomain ||
        (it is ContinuousProduct && it.finiteChoices != null && !it.finiteChoices.hasGuards)
}
