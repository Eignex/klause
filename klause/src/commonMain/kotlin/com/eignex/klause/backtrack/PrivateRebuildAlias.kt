package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.RebuildStep
import com.eignex.klause.presolve.SourceDelta
import com.eignex.klause.presolve.SourceRebuilds
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList

internal object PrivateRebuildAlias {
    fun reduce(problem: Problem, premises: Array<Clause>, cancellation: Cancellation): SourceDelta {
        cancellation.charge(8L)
        if (cancellation() || premises.size != 2 || problem.numFactors < 2 ||
            problem.numIntVars != 0 || problem.numRealVars != 0 ||
            problem.factors[problem.numFactors - 2] !== premises[0] ||
            problem.factors[problem.numFactors - 1] !== premises[1]) return SourceDelta()
        val first = premises[0].literals
        val second = premises[1].literals
        if (first.size != 2 || second.size != 2 || second[0] != Lit.negate(first[0]) ||
            second[1] != Lit.negate(first[1])) return SourceDelta()
        val retained = Lit.variable(first[0])
        val eliminated = Lit.variable(first[1])
        if (retained !in 0 until problem.numBoolVars || eliminated !in 0 until problem.numBoolVars ||
            retained >= eliminated) return SourceDelta()
        val replacement = Lit.make(retained, Lit.isPositive(first[0]) != Lit.isPositive(first[1]))
        cancellation.charge(problem.numFactors.toLong())
        if (cancellation()) return SourceDelta()
        val dropped = IntArrayList(problem.numFactors)
        val added = ArrayList<Factor>()
        for (index in problem.factors.indices) {
            val clause = problem.factors[index] as? Clause ?: return SourceDelta()
            cancellation.charge(clause.literals.size + 1L)
            if (cancellation()) return SourceDelta()
            if (clause.literals.none { Lit.variable(it) == eliminated }) continue
            cancellation.charge(clause.literals.size * 5L + 1L)
            if (cancellation()) return SourceDelta()
            val rewritten = Clause(IntArray(clause.literals.size) {
                val literal = clause.literals[it]
                if (Lit.variable(literal) != eliminated) literal
                else if (Lit.isPositive(literal)) replacement else Lit.negate(replacement)
            })
            dropped.add(index)
            if (!rewritten.tautological) added.add(rewritten)
        }
        cancellation.charge(dropped.size + 2L)
        if (cancellation()) return SourceDelta()
        return SourceDelta(dropped.toIntArray(), added,
            rebuild = SourceRebuilds(listOf(RebuildStep.CopyLiteral(eliminated, replacement))))
    }
}
