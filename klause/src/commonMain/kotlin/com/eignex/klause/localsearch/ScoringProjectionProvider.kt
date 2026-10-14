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
class ScoringProjection internal constructor(
    /** Scoring participation offered by this implementation. */
    val capability: ScoringCapability,
    /** Authority of the score's zero-violation test; heuristic scores require source certification. */
    val assignmentCheck: AssignmentCheckCapability,
    /** Explanation when scoring is deliberately inert. */
    val inertReason: String?,
    private val factory: (Factor, Array<IntDomain>?) -> Invariant,
    internal val builtIn: Boolean,
) {
    /** Build from the supplied engine-view factor and actual move domains, with private mutable payloads. */
    constructor(
        capability: ScoringCapability,
        assignmentCheck: AssignmentCheckCapability,
        inertReason: String? = null,
        factory: (Factor, Array<IntDomain>?) -> Invariant,
    ) : this(capability, assignmentCheck, inertReason, factory, false)

    init {
        require(capability != ScoringCapability.INERT ||
            (assignmentCheck != AssignmentCheckCapability.EXACT && !inertReason.isNullOrBlank())) {
            "inert scoring requires an enforcing peer or source certification, with an explicit reason"
        }
    }

    internal fun create(factor: Factor, domains: Array<IntDomain>?): Invariant {
        check(capability != ScoringCapability.UNSUPPORTED) { "unsupported local-search route" }
        return factory(factor, domains).also {
            check((it === NoInvariant) == (capability == ScoringCapability.INERT)) {
                "local-search capability disagrees with projection"
            }
        }
    }
}

/** Built-in scoring, retaining exact wide-linear sums over the actual move domains. */
object BuiltInScoringProjectionProvider : ScoringProjectionProvider {
    internal fun create(factor: Factor, domains: Array<IntDomain>?): Invariant {
        val capabilities = factor.defaultExecutionCapabilities()
        check(capabilities.scoring != ScoringCapability.UNSUPPORTED) {
            "unsupported local-search route for ${factor::class.simpleName}"
        }
        return factor.buildInvariantProjection(domains).also {
            check((it === NoInvariant) == (capabilities.scoring == ScoringCapability.INERT)) {
                "scoring capability disagrees with projection"
            }
        }
    }

    override fun select(factor: Factor): ScoringProjection? {
        val capabilities = factor.defaultExecutionCapabilities()
        if (capabilities.scoring == ScoringCapability.UNSUPPORTED) return null
        return ScoringProjection(
            capabilities.scoring, capabilities.assignmentCheck, capabilities.inertReason,
            { view, domains -> view.buildInvariantProjection(domains) }, true,
        )
    }
}
