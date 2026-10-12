package com.eignex.klause.solver.result

import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.ModelIdentity

/** A source claim supported by a quadratic optimization attestation. */
enum class QuadraticClaim {
    /** A global objective bound, which need not be attained. */
    Bound,
    /** An attained global optimum. */
    Optimal,
    /** An unattained infimum or supremum, according to the objective sense. */
    UnattainedLimit,
    /** Unlimited objective improvement in the source objective direction. */
    Unbounded,
    /** No source assignment exists under the invocation assumptions. */
    Infeasible,
}

/** An opaque in-memory source attestation; this is not an exported proof file. */
class QuadraticCertificate private constructor(
    internal val model: ModelIdentity,
    internal val scope: Any,
    internal val evidence: Any,
    /** Claim checked by the producing source adapter. */
    val claim: QuadraticClaim,
) {
    internal companion object {
        fun verified(model: ModelIdentity, scope: Any, evidence: Any, claim: QuadraticClaim): QuadraticCertificate =
            QuadraticCertificate(model, scope, evidence, claim)
    }
}

/** A complete source assignment and its exactly evaluated objective. */
data class QuadraticIncumbent(
    /** Source Boolean, integer and exact rational/algebraic real coordinates. */
    val sample: Sample,
    /** Exact source objective value, without objective-direction conversion. */
    val value: AlgebraicReal,
)

/** A global lower bound for minimization or upper bound for maximization. */
data class QuadraticObjectiveBound(
    /** Exact source objective bound. */
    val value: AlgebraicReal,
    /** True when every feasible objective lies strictly beyond this bound. */
    val strict: Boolean,
    /** Source-scoped bound attestation. */
    val certificate: QuadraticCertificate,
)

/** Exact quadratic optimization results, distinct from finite-objective scores. */
sealed interface QuadraticOptimizationResult {
    /** Original source objective direction. */
    val sense: ObjectiveSense

    /** Finite traversal and heuristic statistics, separate from native arithmetic work. */
    val stats: SolveStats

    /** A result retaining a checked source incumbent. */
    sealed interface WithIncumbent : QuadraticOptimizationResult {
        /** Complete checked source assignment and exact objective. */
        val incumbent: QuadraticIncumbent
    }

    /** A checked feasible assignment attaining a proved global bound. */
    data class Optimal(
        override val incumbent: QuadraticIncumbent,
        /** Matching global bound. */
        val bound: QuadraticObjectiveBound,
        /** Completed source optimum attestation. */
        val certificate: QuadraticCertificate,
        override val sense: ObjectiveSense,
        override val stats: SolveStats,
    ) : WithIncumbent

    /** A proved infimum/supremum that no feasible source assignment attains. */
    data class UnattainedLimit(
        /** Feasible point distinct from the unattained limit. */
        override val incumbent: QuadraticIncumbent,
        /** Strict global bound equal to the unattained limit. */
        val bound: QuadraticObjectiveBound,
        /** Completed source nonattainment attestation. */
        val certificate: QuadraticCertificate,
        override val sense: ObjectiveSense,
        override val stats: SolveStats,
    ) : WithIncumbent

    /** A source-valid proof of unlimited improvement, with a checked feasible point. */
    data class Unbounded(
        override val incumbent: QuadraticIncumbent,
        /** Completed source unboundedness attestation. */
        val certificate: QuadraticCertificate,
        override val sense: ObjectiveSense,
        override val stats: SolveStats,
    ) : WithIncumbent

    /** A checked incumbent without a completed source optimum proof. */
    data class BestFound(
        override val incumbent: QuadraticIncumbent,
        /** Separately checked global bound, when available. */
        val bound: QuadraticObjectiveBound?,
        /** Cause of incomplete optimization. */
        val reason: TerminationReason,
        override val sense: ObjectiveSense,
        override val stats: SolveStats,
    ) : WithIncumbent

    /** A completed refutation covering every source branch. */
    data class Infeasible(
        /** Completed source infeasibility attestation. */
        val certificate: QuadraticCertificate,
        override val sense: ObjectiveSense,
        override val stats: SolveStats,
    ) : QuadraticOptimizationResult

    /** No source incumbent or definitive optimization verdict. */
    data class Unknown(
        /** Separately checked global bound, when available. */
        val bound: QuadraticObjectiveBound?,
        /** Cause of incomplete optimization. */
        val reason: TerminationReason,
        override val sense: ObjectiveSense,
        override val stats: SolveStats,
    ) : QuadraticOptimizationResult
}
