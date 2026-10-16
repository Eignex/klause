package com.eignex.klause.solver

import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SampleResult
import com.eignex.klause.solver.result.SatisfyResult

private fun requireMatchingSemantics(previous: EffectiveSourceSemantics?, semantics: EffectiveSourceSemantics) {
    require(previous == null || previous.declared == semantics.declared && previous.effective == semantics.effective) {
        "result belongs to a different source interpretation"
    }
}

internal fun SolveResult.withSourceSemantics(semantics: EffectiveSourceSemantics): SolveResult {
    requireMatchingSemantics(this.semantics, semantics)
    if (this.semantics === semantics &&
        (this !is SolveResult.Sat || assignment.semantics === semantics)
    ) return this
    return when (this) {
        is SolveResult.Sat -> copy(assignment = assignment.withSourceSemantics(semantics), semantics = semantics)
        is SolveResult.Unsat -> copy(semantics = semantics)
        is SolveResult.Unknown -> copy(semantics = semantics)
    }
}

internal fun SampleResult.withSourceSemantics(semantics: EffectiveSourceSemantics): SampleResult {
    requireMatchingSemantics(this.semantics, semantics)
    if (this.semantics === semantics &&
        (this !is SampleResult.Found || sample.semantics === semantics)
    ) return this
    return when (this) {
        is SampleResult.Found -> copy(sample = sample.withSourceSemantics(semantics), semantics = semantics)
        is SampleResult.Infeasible -> copy(semantics = semantics)
        is SampleResult.Unknown -> copy(semantics = semantics)
    }
}

internal fun MinimizeResult.withSourceSemantics(semantics: EffectiveSourceSemantics): MinimizeResult {
    requireMatchingSemantics(this.semantics, semantics)
    if (this.semantics === semantics &&
        (this !is MinimizeResult.WithSample || sample.semantics === semantics)
    ) return this
    return when (this) {
        is MinimizeResult.Optimal -> copy(sample = sample.withSourceSemantics(semantics), semantics = semantics)
        is MinimizeResult.BestFound -> copy(sample = sample.withSourceSemantics(semantics), semantics = semantics)
        is MinimizeResult.Unbounded -> copy(sample = sample.withSourceSemantics(semantics), semantics = semantics)
        is MinimizeResult.Infeasible -> copy(semantics = semantics)
        is MinimizeResult.Unknown -> copy(semantics = semantics)
    }
}

internal fun MinimizeResult.WithSample.withSourceSemantics(
    semantics: EffectiveSourceSemantics,
): MinimizeResult.WithSample = (this as MinimizeResult).withSourceSemantics(semantics) as MinimizeResult.WithSample

internal fun SatisfyResult.withSourceSemantics(semantics: EffectiveSourceSemantics): SatisfyResult {
    requireMatchingSemantics(this.semantics, semantics)
    if (this.semantics === semantics &&
        (this !is SatisfyResult.Sat || sample.semantics === semantics)
    ) return this
    return when (this) {
        is SatisfyResult.Sat -> copy(sample = sample.withSourceSemantics(semantics), semantics = semantics)
        is SatisfyResult.UnsatUnderAssumptions -> copy(semantics = semantics)
        is SatisfyResult.GloballyUnsat -> copy(semantics = semantics)
        is SatisfyResult.Unknown -> copy(semantics = semantics)
    }
}
