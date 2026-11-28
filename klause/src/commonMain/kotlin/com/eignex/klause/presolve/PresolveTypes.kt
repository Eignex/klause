package com.eignex.klause.presolve

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.util.arrays.emptyIntArray

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
) {
    /** Objective over [problem], when a pass substituted an objective coordinate. */
    var objective: LinearObjective? = null
        internal set

    internal var mapping: SourceMapping =
        SourceMapping(problem, problem, TransformationGuarantees.IDENTITY, lift = reconstruct)
}

internal class SourcePresolved(
    // The input itself when no pass fired.
    val problem: Problem,
    // In first-fire order.
    val passesFired: List<PresolvePass> = emptyList(),
    val infeasible: Boolean = false,
    // Recovers eliminated columns in reverse transformation order.
    val rebuild: SourceRebuilds = SourceRebuilds.NONE,
    val objective: LinearObjective? = null,
)

// A source pass's change to its input. Narrower than [PassDelta]: no finite domains, and reconstruction as
// lane-neutral data rather than a closure over one lane's witness, so the type states what a pass running
// before any finite projection may produce. [bounds] is a model-level range (how far a column can reach),
// not a finite domain (which values it may take).
internal class SourceDelta(
    val droppedIndices: IntArray = emptyIntArray(),
    // Appended after the retained input factors.
    val addedFactors: List<Factor> = emptyList(),
    // Null when every column's range is left as declared.
    val bounds: IntBounds? = null,
    val infeasible: Boolean = false,
    val rebuild: SourceRebuilds = SourceRebuilds.NONE,
) {
    var objective: LinearObjective? = null

    // [rebuild] counts: an empty delta is never applied, so a pass that only eliminated a column would
    // otherwise lose its reconstruction.
    val isEmpty: Boolean
        get() = droppedIndices.isEmpty() && addedFactors.isEmpty() && bounds == null && rebuild.isEmpty

    // A proved range reaches the finite lane as a narrowing of [rootDomains]: the lane branches on values,
    // so a bound not folded into a domain would never be enforced. An emptied domain is a refutation.
    fun asPassDelta(rootDomains: Array<IntDomain>): PassDelta {
        val lift = rebuild.asSampleLift()
        val proved = bounds
            ?: return PassDelta(droppedIndices, addedFactors, reconstruct = lift, infeasible = infeasible)
                .also {
                    it.rebuild = rebuild
                    it.objective = objective
                }
        require(proved.size == rootDomains.size) {
            "proved ranges for ${proved.size} columns over a model of ${rootDomains.size}"
        }
        var narrowed: Array<IntDomain>? = null
        for (v in rootDomains.indices) {
            val next = rootDomains[v].narrowedBy(proved, v)
                ?: return PassDelta(droppedIndices, addedFactors, reconstruct = lift, infeasible = true)
                    .also {
                        it.rebuild = rebuild
                        it.objective = objective
                    }
            if (next === rootDomains[v]) continue
            (narrowed ?: rootDomains.copyOf().also { narrowed = it })[v] = next
        }
        return PassDelta(droppedIndices, addedFactors, narrowed, lift, infeasible = infeasible)
            .also {
                it.rebuild = rebuild
                it.objective = objective
            }
    }
}

// Null when nothing remains.
private fun IntDomain.narrowedBy(bounds: IntBounds, v: Int): IntDomain? {
    var domain = this
    if (bounds.hasLower(v) && bounds.lower(v) > domain.min) {
        if (bounds.lower(v) > domain.max) return null
        domain = domain.withMinAtLeast(bounds.lower(v))
    }
    if (bounds.hasUpper(v) && bounds.upper(v) < domain.max) {
        if (bounds.upper(v) < domain.min) return null
        domain = domain.withMaxAtMost(bounds.upper(v))
    }
    return domain
}

/** A single pass's explicit change to its input problem. */
class PassDelta(
    /** Indices of input factors removed or replaced. */
    val droppedIndices: IntArray = emptyIntArray(),
    /** New or replacement factors to append after the retained input factors. */
    val addedFactors: List<Factor> = emptyList(),
    /** Directly tightened integer domains, or `null` when unchanged. */
    val domains: Array<IntDomain>? = null,
    /** Lifts a transformed sample to this pass's input variable space. */
    val reconstruct: ((Sample) -> Sample)? = null,
    /** Whether the pass proved infeasibility. */
    val infeasible: Boolean = false,
) {
    /** Replacement objective committed with this delta. */
    var objective: LinearObjective? = null
        internal set

    // Nonempty steps describe the entire finite reconstruction, including every written column.
    internal var rebuild: SourceRebuilds = SourceRebuilds.NONE

    /** Whether the pass left factors, domains and reconstruction unchanged. */
    val isEmpty: Boolean get() = droppedIndices.isEmpty() && addedFactors.isEmpty() &&
        domains == null && reconstruct == null && rebuild.isEmpty
}

// A transform offered to the source lane may only rewrite factors and refute; a domain or sample lift is
// rejected rather than dropped. A finite domain is not weakened into a range either: its hull would state
// a bound the pass never proved, and its holes would be lost.
internal fun PassDelta.asSourceDelta(): SourceDelta {
    require(domains == null && reconstruct == null) { "source presolve may only rewrite factors" }
    return SourceDelta(droppedIndices, addedFactors, infeasible = infeasible)
}
