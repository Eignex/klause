package com.eignex.klause.backtrack

import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.SingleIntObjective
import com.eignex.klause.solver.result.SharingChannel
import kotlin.math.ceil
import kotlin.time.TimeSource

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
    private val problem: BakedProblem,
    private val session: PropagationSession,
    private val params: BacktrackParams,
    private val singleObj: SingleIntObjective?,
) {
    private var lastPublishedFloor = Double.NEGATIVE_INFINITY

    /** Import a peer-proven objective lower bound, tightening this arm's objective variable. */
    fun applySharedFloor() = timed(SharingChannel.Floor) {
        val supplier = params.objectiveLowerBoundSupplier ?: return@timed
        val obj = singleObj?.takeIf { it.ascending } ?: return@timed
        val bound = supplier()
        if (!bound.isFinite()) return@timed
        val floor = ceil(bound)
        if (floor in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()) {
            session.implyIntAtLeast(obj.varId, floor.toLong())
        }
    }

    /** Publish this arm's objective floor (the objective variable's current root lower bound) when it
     *  has risen, so peers can import it. Level-0 only. */
    fun publishFloor() = timed(SharingChannel.Floor) {
        val sink = params.objectiveLowerBoundSink ?: return@timed
        val obj = singleObj?.takeIf { it.ascending } ?: return@timed
        if (session.decisionLevel != 0) return@timed
        val floor = session.intDomain(obj.varId).min.toDouble()
        if (floor > lastPublishedFloor) {
            lastPublishedFloor = floor
            sink(floor)
        }
    }

    /** Import peers' globally-valid level-0 variable tightenings (import only — level-0 domains here
     *  may carry this arm's incumbent-relative fixings, which are not global). */
    fun importGlobalVarBounds() = timed(SharingChannel.Bounds) {
        val lower = params.globalVarLowerSupplier ?: return@timed
        val upper = params.globalVarUpperSupplier ?: return@timed
        if (session.decisionLevel != 0) return@timed
        val used = params.globalVarImportSink
        for (v in 0 until problem.numIntVars) {
            val lo = lower(v)
            if (lo != Long.MIN_VALUE && lo > session.intDomain(v).min) {
                session.implyIntAtLeast(v, lo)
                used?.invoke(v, true)
            }
            val hi = upper(v)
            if (hi != Long.MAX_VALUE && hi < session.intDomain(v).max) {
                session.implyIntAtMost(v, hi)
                used?.invoke(v, false)
            }
        }
    }

    /** Publish this arm's root variable tightenings (any narrowed past its root domain). */
    fun publishGlobalVarBounds() = timed(SharingChannel.Bounds) {
        val sink = params.globalVarBoundSink ?: return@timed
        if (session.decisionLevel != 0) return@timed
        for (v in 0 until problem.numIntVars) {
            val d = session.intDomain(v)
            val root = problem.rootIntDomain(v)
            if (d.min > root.min || d.max < root.max) sink(v, d.min, d.max)
        }
    }

    // Charge [block]'s time to [channel] when the portfolio measures this arm's sharing.
    private inline fun timed(channel: SharingChannel, block: () -> Unit) {
        val timer = params.sharingTimer ?: return block()
        val start = TimeSource.Monotonic.markNow()
        block()
        timer(channel, start.elapsedNow().inWholeNanoseconds)
    }
}
