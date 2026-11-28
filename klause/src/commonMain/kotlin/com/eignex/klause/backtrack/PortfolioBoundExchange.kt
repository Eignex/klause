package com.eignex.klause.backtrack

import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.SingleIntObjective
import com.eignex.klause.solver.result.SharingChannel
import kotlin.math.ceil
import kotlin.time.TimeSource

// Level-0 bound exchange with sibling portfolio arms: imports a peer objective floor and globally valid variable
// tightenings, and republishes this arm's own. Each method is a no-op unless its supplier or sink is wired and,
// for the variable-bound paths, unless the session is at decision level 0.
internal class PortfolioBoundExchange(
    private val problem: BakedProblem,
    private val session: PropagationSession,
    private val params: BacktrackParams,
    private val singleObj: SingleIntObjective?,
    // The least optimality-gap cutoff a prune of this arm relied on: root facts hold only below it.
    private val gapFloor: () -> Double = { Double.POSITIVE_INFINITY },
) {
    private var lastPublishedFloor = Double.NEGATIVE_INFINITY

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

    fun publishFloor() = timed(SharingChannel.Floor) {
        val sink = params.objectiveLowerBoundSink ?: return@timed
        val obj = singleObj?.takeIf { it.ascending } ?: return@timed
        if (session.decisionLevel != 0) return@timed
        // A root floor derived after a gap prune bounds only the points below that prune's cutoff.
        val floor = minOf(session.intDomain(obj.varId).min.toDouble(), gapFloor())
        if (floor > lastPublishedFloor) {
            lastPublishedFloor = floor
            sink(floor)
        }
    }

    // Import only: level-0 domains here may carry this arm's incumbent-relative fixings, which are not global.
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

    fun publishGlobalVarBounds() = timed(SharingChannel.Bounds) {
        val sink = params.globalVarBoundSink ?: return@timed
        if (session.decisionLevel != 0) return@timed
        for (v in 0 until problem.numIntVars) {
            val d = session.intDomain(v)
            val root = problem.rootIntDomain(v)
            if (d.min > root.min || d.max < root.max) sink(v, d.min, d.max)
        }
    }

    private inline fun timed(channel: SharingChannel, block: () -> Unit) {
        val timer = params.sharingTimer ?: return block()
        val start = TimeSource.Monotonic.markNow()
        block()
        timer(channel, start.elapsedNow().inWholeNanoseconds)
    }
}
