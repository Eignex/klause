package com.eignex.klause.localsearch

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.AssignmentCheckCapability
import com.eignex.klause.solver.ScoringCapability
import com.eignex.klause.solver.defaultExecutionCapabilities

/** Selects local-search semantics from immutable factor data; null refuses scoring. */
fun interface ScoringProjectionProvider {
    /** Select during preparation, before a worker owns an assignment or invariant payload. */
    fun select(factor: Factor): ScoringProjection?
}

/** A scoring role coupled to its domain-sensitive implementation factory. */
class ScoringProjection(
    /** Scoring participation offered by this implementation. */
    val capability: ScoringCapability,
    /** Authority of the score's zero-violation test; heuristic scores require source certification. */
    val assignmentCheck: AssignmentCheckCapability,
    /** Explanation when scoring is deliberately inert. */
    val inertReason: String? = null,
    private val factory: (Array<IntDomain>?) -> Invariant,
) {
    init {
        require(capability != ScoringCapability.INERT ||
            (assignmentCheck != AssignmentCheckCapability.EXACT && !inertReason.isNullOrBlank())) {
            "inert scoring requires an enforcing peer or source certification, with an explicit reason"
        }
    }

    internal fun create(domains: Array<IntDomain>?): Invariant {
        check(capability != ScoringCapability.UNSUPPORTED) { "unsupported local-search route" }
        return factory(domains).also {
            check((it === NoInvariant) == (capability == ScoringCapability.INERT)) {
                "local-search capability disagrees with projection"
            }
        }
    }
}

/** Built-in scoring, retaining exact wide-linear sums over the actual move domains. */
object BuiltInScoringProjectionProvider : ScoringProjectionProvider {
    override fun select(factor: Factor): ScoringProjection? {
        val capabilities = factor.defaultExecutionCapabilities()
        if (capabilities.scoring == ScoringCapability.UNSUPPORTED) return null
        return ScoringProjection(
            capabilities.scoring, capabilities.assignmentCheck, capabilities.inertReason,
            factor::buildInvariantProjection,
        )
    }
}
