package com.eignex.klause.presolve

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.EmptyIntArray

/** A presolve transformation together with its solution reconstruction. */
class Presolved(
    /** The transformed model. */
    val problem: Problem,
    /** Reconstructs a transformed-model sample in the original variable space. */
    val reconstruct: (Sample) -> Sample,
    /** Problem-stage passes that changed the model, in first-fire order. */
    val passesFired: List<PresolvePass> = emptyList(),
    /** Whether presolve proved the input infeasible. */
    val infeasible: Boolean = false,
)

/** A single pass's explicit change to its input problem. */
class PassDelta(
    /** Indices of input factors removed or replaced. */
    val droppedIndices: IntArray = EmptyIntArray,
    /** New or replacement factors to append after the retained input factors. */
    val addedFactors: List<Factor> = emptyList(),
    /** Directly tightened integer domains, or `null` when unchanged. */
    val domains: Array<IntDomain>? = null,
    /** Lifts a transformed sample to this pass's input variable space. */
    val reconstruct: ((Sample) -> Sample)? = null,
    /** Whether the pass proved infeasibility. */
    val infeasible: Boolean = false,
) {
    /** Whether the pass left factors and domains unchanged. */
    val isEmpty: Boolean get() = droppedIndices.isEmpty() && addedFactors.isEmpty() && domains == null
}
