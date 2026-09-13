package com.eignex.klause.lp.bounding

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.InprocessingPass
import com.eignex.klause.backtrack.RootLpDutyCycle
import com.eignex.klause.presolve.harvestEpochBounds
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.util.Cancellation
import kotlin.time.TimeSource

internal class LpEpochPass(private val engine: LpEngine) : InprocessingPass {
    override val preservesVariables: Boolean get() = true
    private val dutyCycle = RootLpDutyCycle()
    private var exported = false
    var runs = 0
        private set
    var exports = 0
        private set
    var declines = 0
        private set

    override fun onRoot(session: PropagationSession, params: BacktrackParams) {
        if (params.lpRootTidy || params.lpEpochs) {
            engine.observeEpoch("root_opportunities")
            runEpoch(session, params)
        }
    }

    override fun run(session: PropagationSession, params: BacktrackParams) {
        if (params.lpEpochs) {
            engine.observeEpoch("restart_opportunities")
            runEpoch(session, params)
        }
    }

    private fun runEpoch(session: PropagationSession, params: BacktrackParams) {
        if (!params.assumptions.isEmpty || session.decisionLevel != 0 ||
            !engine.epochRootAllowed || session.problem !== engine.problem || params.cancellation()
        ) {
            return
        }
        val before = engine.totalSolveWork()
        if (!dutyCycle.allows(before)) {
            engine.observeEpoch("duty_cycle_skips")
            return
        }
        runs++
        val start = TimeSource.Monotonic.markNow()
        val limit = minOf(engine.params.lpPlan.rootMaxWork, LP_EPOCH_MAX_WORK).coerceAtLeast(1L)
        val token = Cancellation {
            params.cancellation() || engine.params.cancellation() ||
                engine.totalSolveWork() - before >= limit ||
                start.elapsedNow().inWholeMilliseconds >= LP_EPOCH_MAX_MILLIS
        }
        try {
            // The source problem and shave budget are unchanged across restarts; repeating a completed
            // source-only harvest cannot use the live cutoff to prove anything stronger.
            if (!exported) {
                val facts = harvestEpochBounds(engine, token)
                for (bound in facts) {
                    if (token()) break
                    exports++
                    engine.observeEpoch("source_bounds")
                    params.globalVarBoundSink?.invoke(bound.varId, bound.lo, bound.hi)
                    if (session.implyIntAtLeast(bound.varId, bound.lo) is PropagationResult.Unsat ||
                        session.implyIntAtMost(bound.varId, bound.hi) is PropagationResult.Unsat
                    ) {
                        return
                    }
                }
                if (!token()) exported = true
            }
            if (!token() && !engine.rebuildEpoch(session, token)) declines++
        } finally {
            engine.noteEpochWork(1L)
            dutyCycle.record(before, engine.totalSolveWork())
            engine.chargeRootLpWall(start.elapsedNow().inWholeMilliseconds)
        }
    }

    override fun reset() {
        dutyCycle.reset()
        exported = false
        engine.discardEpoch()
    }
}

private const val LP_EPOCH_MAX_WORK = 1_000_000L
private const val LP_EPOCH_MAX_MILLIS = 100L
