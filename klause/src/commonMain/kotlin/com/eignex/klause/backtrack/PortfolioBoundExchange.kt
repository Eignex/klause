package com.eignex.klause.backtrack

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.SingleIntObjective
import kotlin.math.ceil

/**
 * Level-0 bound exchange with sibling portfolio arms. At the root the branch-and-bound engine
 * imports bounds proven by peers — a tighter objective floor and globally-valid variable
 * tightenings — and republishes its own, so a bound proven mid-search on any arm propagates through
 * the pool. Every method is a no-op unless the corresponding [BacktrackParams] supplier/sink is wired
 * (i.e. unless this solve runs inside a sharing portfolio) and, for the variable-bound paths, unless
 * the session is at decision level 0.
 *
 * Holds only the last-published objective floor watermark; the incumbent itself stays with the
 * engine. Mutates the shared [PropagationSession] via `imply*` (monotone, sound tightenings).
 */
internal class PortfolioBoundExchange(
    private val problem: Problem,
    private val session: PropagationSession,
    private val params: BacktrackParams,
    private val singleObj: SingleIntObjective?,
) {
    private var lastPublishedFloor = Double.NEGATIVE_INFINITY

    /** Import a peer-proven objective lower bound, tightening this arm's objective variable. */
    fun applySharedFloor() {
        val supplier = params.objectiveLowerBoundSupplier ?: return
        val obj = singleObj?.takeIf { it.ascending } ?: return
        val bound = supplier()
        if (!bound.isFinite()) return
        val floor = ceil(bound)
        if (floor in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()) {
            session.implyIntAtLeast(obj.varId, floor.toLong())
        }
    }

    /** Publish this arm's objective floor (the objective variable's current root lower bound) when it
     *  has risen, so peers can import it. Level-0 only. */
    fun publishFloor() {
        val sink = params.objectiveLowerBoundSink ?: return
        val obj = singleObj?.takeIf { it.ascending } ?: return
        if (session.decisionLevel != 0) return
        val floor = session.intDomain(obj.varId).min.toDouble()
        if (floor > lastPublishedFloor) {
            lastPublishedFloor = floor
            sink(floor)
        }
    }

    /** Import peers' globally-valid level-0 variable tightenings (import only — level-0 domains here
     *  may carry this arm's incumbent-relative fixings, which are not global). */
    fun importGlobalVarBounds() {
        val lower = params.globalVarLowerSupplier ?: return
        val upper = params.globalVarUpperSupplier ?: return
        if (session.decisionLevel != 0) return
        for (v in 0 until problem.numIntVars) {
            val lo = lower(v)
            if (lo != Long.MIN_VALUE) session.implyIntAtLeast(v, lo)
            val hi = upper(v)
            if (hi != Long.MAX_VALUE) session.implyIntAtMost(v, hi)
        }
    }

    /** Publish this arm's root variable tightenings (any narrowed past the declared domain). */
    fun publishGlobalVarBounds() {
        val sink = params.globalVarBoundSink ?: return
        if (session.decisionLevel != 0) return
        for (v in 0 until problem.numIntVars) {
            val d = session.intDomain(v)
            val declared = problem.intDomains[v]
            if (d.min > declared.min || d.max < declared.max) sink(v, d.min, d.max)
        }
    }
}
