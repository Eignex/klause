package com.eignex.klause.solver.result

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceResultEvidence
import com.eignex.klause.solver.evidence.SourceScope

/** Exact source linear authority, with incumbent and bound retained independently of completion. */
class LinearOptimizationResult internal constructor(
    /** Original source revision, restrictions, objective identity and direction. */
    val scope: SourceScope,
    /** Checked complete claim, absent when required coverage or certification is incomplete. */
    val terminal: SourceEvidence?,
    /** Independently checked global objective bound, absent for branch-only evidence. */
    val bound: SourceEvidence?,
    /** Best accepted source witness; it may differ from an approach or recession base. */
    val incumbent: Sample?,
    /** Exact objective at [incumbent], in the original source direction. */
    val value: BigFraction?,
    /** Completion or interruption reason, separate from the mathematical claim. */
    val reason: TerminationReason,
    /** Cumulative traversal and numerical diagnostics. */
    val stats: SolveStats,
) {
    /** Inspectable original-source derivations, including retained partial witness and bound data. */
    val evidence: SourceResultEvidence = SourceResultEvidence(scope, buildList {
        incumbent?.let { add(SourceEvidence(scope, SourceClaim.Witness(it), SourceDerivation.SourceWitness)) }
        bound?.let(::add)
        terminal?.let(::add)
    })
}
