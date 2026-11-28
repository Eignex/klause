package com.eignex.klause.lp.cut

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.CutExpression
import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.lp.engine.CutPremise
import com.eignex.klause.lp.engine.CutProofFact
import com.eignex.klause.lp.engine.CutProvenance
import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.lp.rootDomainOf
import com.eignex.klause.lp.statesBothBounds
import com.eignex.klause.lp.statesLowerBound
import com.eignex.klause.lp.statesUpperBound
import com.eignex.klause.propagation.PropagationSession
import com.eignex.util.numbers.BigFraction

internal class CutContext(
    val problem: Problem,
    val relaxation: LpRelaxation,
    val primal: DoubleArray,
    val session: PropagationSession,
) {
    fun primalOf(col: Int): Double = if (col in primal.indices) primal[col] else 0.0

    fun rootDomain(v: Int): IntDomain = problem.rootDomainOf(v)

    // A cut whose constants read a root endpoint is global only when the model itself states that bound.
    fun statesLowerBound(v: Int): Boolean = problem.statesLowerBound(v)

    fun statesUpperBound(v: Int): Boolean = problem.statesUpperBound(v)

    fun statesBothBounds(v: Int): Boolean = problem.statesBothBounds(v)

    fun withIntervalPremises(cut: Cut, variables: IntArray): Cut {
        if (cut.global) return cut
        val map = relaxation.sourceMap ?: return cut
        val facts = ArrayList<CutProofFact>()
        for (variable in variables) {
            val domain = session.intDomain(variable)
            val expression = CutExpression(mapOf(CutSource(CutSourceKind.INTEGER, variable) to BigFraction.ONE))
            for (upper in listOf(false, true)) {
                val premise = CutPremise.Bound(
                    expression, upper, BigFraction.ofLong(if (upper) domain.max else domain.min),
                )
                facts.add(CutProofFact(premise, map.isGlobal(premise)))
            }
        }
        val proof = CutProvenance(map.model, map.epoch, facts, map.assumptions, slack = cut.slack)
        return Cut(cut.cols, cut.coeffs, cut.rel, cut.rhs, global = proof.global, provenance = proof, slack = cut.slack)
    }
}

// Returns only cuts the LP point violates, so the LP does not grow with rows it does not need.
internal interface CutSeparator {
    val family: CutFamily get() = CutFamily.UNSPECIFIED
    fun separate(ctx: CutContext): List<Cut>
}

// A bound from live intervals equal to the declared ones holds at every solution. A root box that closed a
// side the model left open bounds the search, not the model, so such a column never qualifies.
internal fun liveIntervalsAreDeclared(ctx: CutContext, vars: IntArray): Boolean {
    for (v in vars) {
        if (!ctx.statesBothBounds(v)) return false
        val live = ctx.session.intDomain(v)
        val root = ctx.rootDomain(v)
        if (live.min != root.min || live.max != root.max) return false
    }
    return true
}

// Hole-aware: the live domain is a subset of the root one, so equal sizes mean equal value sets.
internal fun liveDomainsAreDeclared(ctx: CutContext, vars: IntArray): Boolean {
    for (v in vars) {
        if (!ctx.statesBothBounds(v)) return false
        if (ctx.session.intDomain(v).valueCount != ctx.rootDomain(v).valueCount) return false
    }
    return true
}
