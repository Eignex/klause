package com.eignex.klause.solver.evidence

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.pipeline.PreparedSourcePlan
import com.eignex.klause.util.Cancellation

/** Bind optional capture to a prepared plan's original exact IR and explicit invocation pins. */
fun PreparedSourcePlan.sourceResultCapture(
    revision: SourceRevision,
    assumptions: Assumptions = Assumptions.None,
    objectiveIdentity: String? = null,
    cancellation: Cancellation = Cancellation.Never,
): SourceResultCapture? {
    val original = originalSource as? Problem ?: return null
    if (!assumptions.deductions.isEmpty || assumptions.bools.keys.any { it !in 0 until sourceBoolCount } ||
        assumptions.ints.keys.any { it !in 0 until sourceIntCount }
    ) return null
    if ((objective == null) != (objectiveIdentity == null)) return null
    val descriptor = objectiveIdentity?.let {
        SourceObjective(it, if (maximize) ObjectiveSense.MAXIMIZE else ObjectiveSense.MINIMIZE)
    }
    val scope = SourceScope(revision, assumptions.sourceCondition(), descriptor)
    val context = original.sourceProofContext(scope, objective, cancellation) ?: return null
    return SourceResultCapture(context)
}
