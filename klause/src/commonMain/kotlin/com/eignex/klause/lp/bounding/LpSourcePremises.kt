package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.engine.CutExpression
import com.eignex.klause.lp.engine.CutPremise
import com.eignex.klause.lp.engine.CutProvenance
import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.engine.ceilLong
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.RegisteredTheoryDecision
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchRealValue
import com.eignex.klause.solver.search.explainAtoms
import com.eignex.klause.theory.qflra.SourceBoundAtom
import com.eignex.klause.theory.qflra.SourceBoundTerm
import com.ionspin.kotlin.bignum.integer.BigInteger

internal class LpSourcePremises(private val root: Any) {
    private val names = LinkedHashMap<CutPremise, SearchDecision>()
    private var owner: SearchContext? = null

    fun record(decision: SearchDecision, context: SearchContext) {
        if (owner != null && owner !== context) return
        if (context.atomLiteral(decision) == null) return
        owner = context
        val premise = when (decision) {
            is SearchDecision.Bool -> CutPremise.Literal(decision.literal)

            is SearchDecision.Theory -> {
                val registered = decision.decision as? RegisteredTheoryDecision ?: return
                val atom = registered.payload as? SourceBoundAtom ?: return
                val terms = atom.terms.associate { term ->
                    val source = when (val key = term.source) {
                        is SearchIntValue -> CutSource(CutSourceKind.INTEGER, key.variable)
                        is SearchRealValue -> CutSource(CutSourceKind.REAL, key.variable)
                        else -> return
                    }
                    source to term.coefficient
                }
                CutPremise.Bound(CutExpression(terms), atom.upper, atom.threshold, atom.strict)
            }

            else -> return
        }
        names[premise] = decision
    }

    fun explain(
        proof: CutProvenance,
        context: SearchContext,
        assumptions: Map<String, SearchAtomPremise> = emptyMap(),
        conclusion: SearchDecision? = null,
        maxNodes: Int = 4096,
    ): SearchExplanation? {
        if (proof.model !== root || (owner != null && owner !== context)) return null
        if (maxNodes <= 0 || proof.facts.size.toLong() + proof.assumptions.size >= maxNodes) return null
        val premises = proof.facts.filter { !it.global }.map { premise(it.premise, context) } +
            proof.assumptions.map { assumptions[it] ?: SearchAtomPremise.Unavailable }
        return context.explainAtoms(SearchAtomPremise.All(premises), conclusion, maxNodes)
    }

    private fun premise(fact: CutPremise, context: SearchContext): SearchAtomPremise {
        val direct = names[fact]?.takeIf { active(it, context) }
        if (direct != null) return SearchAtomPremise.Asserted(direct)
        if (fact is CutPremise.Fixed) {
            val expression = CutExpression(mapOf(fact.source to BigFraction.ONE))
            return SearchAtomPremise.All(
                listOf(
                    premise(CutPremise.Bound(expression, false, fact.value), context),
                    premise(CutPremise.Bound(expression, true, fact.value), context),
                ),
            )
        }
        if (fact is CutPremise.Bound) {
            for ((candidate, decision) in names) {
                if (candidate !is CutPremise.Bound || candidate.expression != fact.expression ||
                    candidate.upper != fact.upper || !active(decision, context)
                ) {
                    continue
                }
                val stronger = if (fact.upper) candidate.value < fact.value else candidate.value > fact.value
                if (stronger || (candidate.value == fact.value && (!fact.strict || candidate.strict))) {
                    return SearchAtomPremise.Asserted(decision)
                }
            }
        }
        return SearchAtomPremise.Unavailable
    }

    private fun active(decision: SearchDecision, context: SearchContext): Boolean {
        val literal = context.atomLiteral(decision) ?: return false
        return context.boolValue(literal ushr 1) == (literal and 1 == 0)
    }
}

internal fun lpIntegerBranch(
    variable: Int,
    value: BigFraction,
    context: SearchContext,
    registered: Boolean,
): List<SearchDecision>? {
    if (value.den == BigInteger.ONE) return null
    if (registered) {
        return SourceBoundAtom.integerSplit(
            context,
            listOf(SourceBoundTerm(SearchIntValue(variable), BigFraction.ONE)),
            value,
        )?.alternatives()
    }
    val upper = value.ceilLong() ?: return null
    if (upper == Long.MIN_VALUE) return null
    return listOf(SearchDecision.IntAtMost(variable, upper - 1L), SearchDecision.IntAtLeast(variable, upper))
}
