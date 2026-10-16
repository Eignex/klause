package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.SourceSemanticsOwner

internal val PreparedSourcePlan.sourceSemantics: EffectiveSourceSemantics?
    get() = (originalSource as? SourceSemanticsOwner)?.semantics

private fun requireMatchingSemantics(previous: EffectiveSourceSemantics?, semantics: EffectiveSourceSemantics) {
    require(previous == null ||
        (previous.declared == semantics.declared && previous.effective == semantics.effective)
    ) {
        "execution belongs to a different source interpretation"
    }
}

internal fun OpenTheoryExecution.withSourceSemantics(semantics: EffectiveSourceSemantics?): OpenTheoryExecution {
    if (semantics == null) return this
    requireMatchingSemantics(this.semantics, semantics)
    if (this.semantics === semantics) return this
    return when (this) {
        is OpenTheoryExecution.Satisfy -> copy(result = result.withSourceSemantics(semantics))
        is OpenTheoryExecution.Optimize -> {
            requireMatchingSemantics(acceptedIncumbent?.sample?.semantics, semantics)
            copy(semantics = semantics).also { it.retainPublication(acceptedIncumbent) }
        }
    }
}

internal fun QuadraticOptimizationExecution.withSourceSemantics(
    semantics: EffectiveSourceSemantics?,
): QuadraticOptimizationExecution {
    if (semantics == null) return this
    requireMatchingSemantics(this.semantics, semantics)
    requireMatchingSemantics(acceptedIncumbent?.incumbent?.sample?.semantics, semantics)
    if (this.semantics === semantics) return this
    return copy(semantics = semantics).also { it.retainPublication(acceptedIncumbent) }
}
