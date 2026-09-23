package com.eignex.klause.presolve

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
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
 * [rebuild] recovers the Boolean columns the passes eliminated. Integer columns are still never
 * eliminated here, so a witness needs nothing beyond it.
 */
internal class SourcePresolved(
    /** The transformed model, or the input itself when no pass fired. */
    val problem: Problem,
    /** Source passes that changed the model, in first-fire order. */
    val passesFired: List<PresolvePass> = emptyList(),
    /** Whether the lane proved the input infeasible. */
    val infeasible: Boolean = false,
    /** Recovers the Boolean columns the passes eliminated, in the order they are recovered. */
    val rebuild: SourceRebuilds = SourceRebuilds.NONE,
)

/**
 * A source pass's explicit change to its input problem.
 *
 * Deliberately narrower than [PassDelta]: no finite domains, and a reconstruction stated as data rather
 * than as a closure over one lane's witness, so what a pass running before any finite projection exists
 * may produce is said by the type rather than checked on the way through. [bounds] is the one range it
 * may state, and a model-level range is not a finite domain — it says how far a column can reach, while
 * a domain says which values it may take. [rebuild] covers Boolean columns only; eliminating an integer
 * column needs a step shape that carries values at both lanes' widths, which does not exist yet.
 */
internal class SourceDelta(
    /** Indices of input factors removed or replaced. */
    val droppedIndices: IntArray = EmptyIntArray,
    /** New or replacement factors to append after the retained input factors. */
    val addedFactors: List<Factor> = emptyList(),
    /** Integer ranges the pass proved, or null when it left every column's range as declared. */
    val bounds: IntBounds? = null,
    /** Whether the pass proved infeasibility. */
    val infeasible: Boolean = false,
    /** Recovers the Boolean columns this pass eliminated, or [SourceRebuilds.NONE] when it eliminated none. */
    val rebuild: SourceRebuilds = SourceRebuilds.NONE,
) {
    /**
     * Whether the pass left the factor list, every column's range and every column unchanged.
     *
     * [rebuild] counts: an empty delta is reported `UNCHANGED` and never applied, so a pass that
     * eliminated a column while rewriting nothing else would have its reconstruction silently dropped.
     */
    val isEmpty: Boolean
        get() = droppedIndices.isEmpty() && addedFactors.isEmpty() && bounds == null && rebuild.isEmpty

    /**
     * This change as the finite lane's delta, for a source pass running over a baked model.
     *
     * A proved range reaches the finite lane as a narrowing of [rootDomains], not as the range itself:
     * the lane branches on values, so a bound it cannot fold into a domain would be a bound it never
     * enforces. Intersecting can empty a column that neither endpoint crossed, which is a refutation.
     *
     * [rebuild] becomes that lane's sample lift, which is the same steps read over its own witness.
     */
    fun asPassDelta(rootDomains: Array<IntDomain>): PassDelta {
        val lift = rebuild.asSampleLift()
        val proved = bounds
            ?: return PassDelta(droppedIndices, addedFactors, reconstruct = lift, infeasible = infeasible)
        require(proved.size == rootDomains.size) {
            "proved ranges for ${proved.size} columns over a model of ${rootDomains.size}"
        }
        var narrowed: Array<IntDomain>? = null
        for (v in rootDomains.indices) {
            val next = rootDomains[v].narrowedBy(proved, v)
                ?: return PassDelta(droppedIndices, addedFactors, reconstruct = lift, infeasible = true)
            if (next === rootDomains[v]) continue
            (narrowed ?: rootDomains.copyOf().also { narrowed = it })[v] = next
        }
        return PassDelta(droppedIndices, addedFactors, narrowed, lift, infeasible = infeasible)
    }
}

/** This domain intersected with column [v]'s range in [bounds], or null when nothing remains. */
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

/**
 * This change as the source lane's delta, for a transform whose one implementation serves both lanes.
 *
 * The `require` is the boundary the narrower type states: a transform offered to the source lane may
 * rewrite factors and refute, and nothing else — a domain or a sample lift arriving here would be
 * dropped on the floor rather than rejected. A finite domain is not weakened into a proved range on the
 * way through either: its hull would state a bound the pass never proved of the columns it left alone,
 * and its holes would be lost. A transform that proves ranges for both lanes states them as ranges.
 */
internal fun PassDelta.asSourceDelta(): SourceDelta {
    require(domains == null && reconstruct == null) { "source presolve may only rewrite factors" }
    return SourceDelta(droppedIndices, addedFactors, infeasible = infeasible)
}
