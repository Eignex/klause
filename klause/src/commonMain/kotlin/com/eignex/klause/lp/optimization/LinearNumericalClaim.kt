package com.eignex.klause.lp.optimization

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.engine.FLOAT_EVIDENCE_TOLERANCE
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.util.Cancellation
import kotlin.math.abs

internal class LinearNumericalClaim private constructor(
    source: SourceProofContext,
    val bound: Double?,
    val infeasible: Boolean,
) {
    val scope = source.scope
    private val definitions = source.linearDefinitionIdentity
    private val semantics = source.semantics
    private val checker = source.semanticsChecker

    fun appliesTo(other: SourceProofContext, budget: SourceLpBudget, cancellation: Cancellation): Boolean =
        appliesUnder(other, other.scope.condition, budget, cancellation)

    fun appliesUnder(
        other: SourceProofContext, condition: SourceCondition, budget: SourceLpBudget, cancellation: Cancellation,
    ): Boolean {
        if (cancellation() || !budget.context.certificationPolicy.numericalDeductions ||
            definitions !== other.linearDefinitionIdentity || semantics !== other.semantics ||
            checker !== other.semanticsChecker || scope.revision != other.scope.revision ||
            scope.objective != other.scope.objective) return false
        if (scope.condition === condition) return !cancellation()
        val work = 1L + 128L * (scope.condition.comparisonSize + condition.comparisonSize)
        if (budget.funding?.let { it.availableWork < work } == true) return false
        budget.observe(preparation = work)
        return !cancellation() && condition.implies(scope.condition) && !cancellation()
    }

    fun isFor(other: SourceProofContext): Boolean =
        definitions === other.linearDefinitionIdentity && semantics === other.semantics &&
            checker === other.semanticsChecker && scope.revision == other.scope.revision &&
            scope.objective == other.scope.objective && scope.condition === other.scope.condition

    fun reaches(incumbent: BigFraction): Boolean {
        val value = bound ?: return false
        val standing = incumbent.toDouble()
        if (!standing.isFinite()) return false
        val slack = FLOAT_EVIDENCE_TOLERANCE * maxOf(1.0, abs(standing))
        return when (scope.objective?.sense) {
            ObjectiveSense.MINIMIZE -> value >= standing - slack
            ObjectiveSense.MAXIMIZE -> value <= standing + slack
            null -> false
        }
    }

    fun strongerThan(other: LinearNumericalClaim): Boolean {
        if (definitions !== other.definitions || scope != other.scope || semantics !== other.semantics ||
            checker !== other.checker) return false
        if (infeasible) return true
        if (other.infeasible || bound == null) return false
        val before = other.bound ?: return true
        return if (scope.objective?.sense == ObjectiveSense.MINIMIZE) bound > before else bound < before
    }

    fun improvesOver(other: LinearNumericalClaim?): Boolean {
        if (other == null) return true
        if (!strongerThan(other)) return false
        if (infeasible) return !other.infeasible
        val before = other.bound ?: return true
        val after = bound ?: return false
        val tolerance = FLOAT_EVIDENCE_TOLERANCE * maxOf(1.0, abs(before), abs(after))
        return abs(after - before) > tolerance
    }

    companion object {
        fun create(
            source: SourceProofContext, bound: Double?, infeasible: Boolean,
            budget: SourceLpBudget, cancellation: Cancellation,
        ): LinearNumericalClaim? {
            if (cancellation() || !budget.context.certificationPolicy.numericalDeductions ||
                (bound == null && !infeasible) || bound?.isFinite() == false) return null
            val funding = budget.funding
            if (funding != null && (funding.availableWork < 1L || funding.remainingAllocation < 128L)) return null
            funding?.charge(0L, 128L)
            budget.observe(preparation = 1L)
            return if (cancellation()) null else LinearNumericalClaim(source, bound, infeasible)
                .takeUnless { cancellation() }
        }
    }
}

internal fun strongerNumericalClaim(left: LinearNumericalClaim?, right: LinearNumericalClaim?): LinearNumericalClaim? =
    when {
        left == null -> right
        right == null -> left
        left.strongerThan(right) -> left
        else -> right
    }
