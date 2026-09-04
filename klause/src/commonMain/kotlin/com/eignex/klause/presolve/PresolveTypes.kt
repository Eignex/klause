package com.eignex.klause.presolve

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.EmptyIntArray

/** A presolve transformation together with its solution reconstruction. */
class Presolved(
    /** The transformed model. */
    val problem: BakedProblem,
    /** Reconstructs a transformed-model sample in the original variable space. */
    val reconstruct: (Sample) -> Sample,
    /** Problem-stage passes that changed the model, in first-fire order. */
    val passesFired: List<PresolvePass> = emptyList(),
    /** Whether presolve proved the input infeasible. */
    val infeasible: Boolean = false,
)

/**
 * What the source lane made of a canonical model: the rewritten declarations and factors, and whether a
 * pass refuted it.
 *
 * No reconstruction: a source pass may not eliminate a column, so a sample of [problem] is already a
 * sample of the model the lane was handed.
 */
internal class SourcePresolved(
    /** The transformed model, or the input itself when no pass fired. */
    val problem: Problem,
    /** Source passes that changed the model, in first-fire order. */
    val passesFired: List<PresolvePass> = emptyList(),
    /** Whether the lane proved the input infeasible. */
    val infeasible: Boolean = false,
)

/**
 * A source pass's explicit change to its input problem.
 *
 * Deliberately narrower than [PassDelta]: no finite domains and no sample lift, so what a pass running
 * before any finite projection exists may produce is stated by the type rather than checked on the way
 * through.
 */
internal class SourceDelta(
    /** Indices of input factors removed or replaced. */
    val droppedIndices: IntArray = EmptyIntArray,
    /** New or replacement factors to append after the retained input factors. */
    val addedFactors: List<Factor> = emptyList(),
    /** Whether the pass proved infeasibility. */
    val infeasible: Boolean = false,
) {
    /** Whether the pass left the factor list unchanged. */
    val isEmpty: Boolean get() = droppedIndices.isEmpty() && addedFactors.isEmpty()

    /** This change as the finite lane's delta, for a source pass running over a baked model. */
    fun asPassDelta(): PassDelta = PassDelta(droppedIndices, addedFactors, infeasible = infeasible)
}

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
