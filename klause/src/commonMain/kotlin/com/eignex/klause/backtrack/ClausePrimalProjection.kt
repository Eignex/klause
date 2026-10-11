package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.BoundedVariableElimination
import com.eignex.klause.presolve.PresolveShared.withSourcePassDelta
import com.eignex.klause.presolve.SourceRebuilds
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter

internal class ClausePrimalProjection private constructor(
    val problem: BakedProblem,
    private val fixed: Array<Boolean?>,
    private val variables: IntArray,
    private val rebuild: SourceRebuilds,
) {
    fun lift(sample: Sample): Sample {
        val values = BooleanArray(fixed.size) { fixed[it] ?: false }
        for (index in variables.indices) values[variables[index]] = sample.bools[index]
        rebuild.rebuildInto(values)
        return Sample(values, longArrayOf())
    }

    class Preparation(val projection: ClausePrimalProjection? = null, val infeasible: Boolean = false)

    companion object {
        fun prepare(
            source: Problem,
            assumptions: Assumptions,
            nativeSat: Boolean?,
            cancellation: Cancellation,
            charge: (Long) -> Unit,
        ): Preparation {
            if (cancellation()) return Preparation()
            val meter = WorkMeter(charge)
            val token = object : Cancellation {
                override fun isCancelled(): Boolean = cancellation()
                override fun deadline() = cancellation.deadline()
                override fun workMeter(): WorkMeter = meter
            }
            val session = PropagationSession(source, token, nativeSat = nativeSat != false)
            val seeded = session.seed(assumptions)
            charge(session.work)
            if (cancellation() || session.fixpointCancelled) return Preparation()
            if (session.isUnsatAtRoot || seeded is PropagationResult.Unsat) return Preparation(infeasible = true)
            val fixed = Array(source.numBoolVars) { session.boolValue(it) }
            val clauses = mutableListOf<Clause>()
            for (factor in source.factors) {
                if (cancellation()) return Preparation()
                val clause = factor as Clause
                charge(clause.literals.size.toLong())
                if (clause.literals.any { fixed[Lit.variable(it)]?.let { value -> Lit.evaluate(it, value) } == true }) {
                    continue
                }
                val remaining = clause.literals.filter { fixed[Lit.variable(it)] == null }.toIntArray()
                if (remaining.isEmpty()) return Preparation(infeasible = true)
                clauses.add(Clause(remaining))
            }
            var reduced = Problem(
                source.numBoolVars, 0, emptyArray(), clauses.toTypedArray(), settings = source.settings,
            )
            val rebuilds = mutableListOf<SourceRebuilds>()
            repeat(2) {
                if (cancellation()) return Preparation()
                val delta = BoundedVariableElimination.eliminateSource(reduced, cancellation = token)
                reduced = reduced.withSourcePassDelta(delta) ?: return Preparation(infeasible = true)
                rebuilds.add(delta.rebuild)
            }
            if (cancellation()) return Preparation()
            val variables = reduced.factors.flatMap { (it as Clause).literals.map(Lit::variable) }
                .distinct().sorted().toIntArray()
            val mapping = IntArray(source.numBoolVars) { -1 }
            for (index in variables.indices) mapping[variables[index]] = index
            val compact: Array<Factor> = reduced.factors.map { factor ->
                val clause = factor as Clause
                charge(clause.literals.size.toLong())
                Clause(IntArray(clause.literals.size) { index ->
                    val literal = clause.literals[index]
                    Lit.make(mapping[Lit.variable(literal)], Lit.isPositive(literal))
                })
            }.toTypedArray()
            val problem = Problem(variables.size, 0, emptyArray(), compact, settings = source.settings).bake(token)
            if (cancellation()) return Preparation()
            return Preparation(ClausePrimalProjection(problem, fixed, variables, SourceRebuilds.compose(rebuilds)))
        }
    }
}
