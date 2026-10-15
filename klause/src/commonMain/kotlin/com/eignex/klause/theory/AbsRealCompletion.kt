package com.eignex.klause.theory

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
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
import com.eignex.klause.solver.search.SearchRealValue
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.solver.search.contribute
import com.eignex.klause.solver.selectedFactorProjections
import com.eignex.klause.theory.qflra.prepareExactLiraSource
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter

internal class AbsRealCompletion(private val source: Problem) {
    private val encoders = source.selectedFactorProjections()

    sealed interface Result {
        class Witness(val reals: List<BigFraction>) : Result
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
        val result = completeWith(sample, counted, metered, solveContext)
        return Completed(if (counted()) Result.Undecided else result, work)
    }

    private fun completeWith(
        sample: Sample,
        cancellation: Cancellation,
        controls: SearchExecutionControls,
        solveContext: LpSolveContext,
    ): Result {
        val encoded = encoders.finiteLinearModel(sample, cancellation) ?: return Result.Undecided
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
                values.put(SearchRealValue(id), value)
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
