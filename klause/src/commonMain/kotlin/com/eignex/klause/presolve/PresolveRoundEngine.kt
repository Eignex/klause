package com.eignex.klause.presolve

import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation

/** Abort a pass schedule after a round makes only a marginal complexity reduction. */
private const val PRESOLVE_ABORT_FRACTION = 0.001

/**
 * Share of the model, in [PresolveRoundEngine.RoundHost.modelSize] units, that other passes must change
 * before a pass above [PresolveTiming.FAST] runs again. Below it the cheap passes absorb the change and
 * the expensive one would mostly re-derive what it found last time.
 */
private const val RERUN_CHANGE_FRACTION = 0.01

/** What running one pass did to the host's model. */
internal enum class PassOutcome {
    /** The pass found nothing to do. */
    UNCHANGED,

    /** The pass rewrote the model. */
    CHANGED,

    /** The pass refuted the model. */
    INFEASIBLE,
}

/** Shared bounded-fixpoint scheduler for the source and finite presolve lanes. */
internal object PresolveRoundEngine {

    /**
     * The per-lane operations the scheduler drives.
     *
     * A host owns the model type its passes read, the change type they return, and where a firing pass's
     * change lands; the scheduler needs only to know what a pass did, so neither type appears here. That
     * is what lets the source lane run over declarations while the finite lane runs over root domains
     * under one schedule.
     */
    interface RoundHost {
        /** Run [pass] over the current model and fold its change in. [slice] caps this pass's own share
         *  of the phase budget; `null` leaves the host's own cancellation in place. */
        fun runPass(pass: PresolvePass, slice: Cancellation?): PassOutcome

        /** Post-pass hook, invoked whether or not the pass fired. */
        fun afterPass(pass: PresolvePass) {}

        /** Current problem complexity, read at round boundaries for the abort check. */
        fun complexity(): Long

        /** Factors plus columns of the current model, the scale [changedUnits] is weighed against. */
        fun modelSize(): Long

        /** Factors dropped or added plus columns narrowed, summed over every change folded in so far. */
        fun changedUnits(): Long
    }

    /** The changes made by one bounded pass schedule. */
    class Result(val fired: List<PresolvePass>, val infeasible: Boolean)

    /**
     * Drive [passes] to their bounded fixpoint through [host].
     *
     * Any change re-enables every [PresolveTiming.FAST] pass. A costlier pass runs again only once the
     * other passes have changed `RERUN_CHANGE_FRACTION` of the model it last ran over, so a small fold
     * elsewhere does not buy a second symmetry search or probe.
     */
    fun run(
        passes: List<PresolvePass>,
        maxRounds: Int,
        cancellation: Cancellation,
        budget: PresolveBudget?,
        host: RoundHost,
    ): Result {
        var version = 0
        val ranAtVersion = HashMap<PresolvePass, Int>()
        // The host's change counter just after an expensive pass last ran, so its own change never counts
        // toward re-running it, and the model size it ran over.
        val unitsAfterRun = HashMap<PresolvePass, Long>()
        val sizeAtRun = HashMap<PresolvePass, Long>()
        val fired = LinkedHashSet<PresolvePass>()
        val exhausted = HashSet<PresolvePass>()
        var infeasible = false
        var round = 0
        var roundStartComplexity = host.complexity()

        fun due(pass: PresolvePass): Boolean {
            if (pass in exhausted || ranAtVersion[pass] == version) return false
            val after = unitsAfterRun[pass] ?: return true
            val changedSince = host.changedUnits() - after
            return changedSince > 0 && changedSince >= RERUN_CHANGE_FRACTION * sizeAtRun.getValue(pass)
        }

        while (round < maxRounds && !cancellation()) {
            var ranAny = false
            var eligible = passes.count(::due)
            for (pass in passes) {
                if (cancellation()) break
                if (!due(pass)) continue
                ranAtVersion[pass] = version
                ranAny = true
                val slice = budget?.let { sliceOf(it, cancellation, eligible) }
                eligible--
                val size = host.modelSize()
                when (host.runPass(pass, slice)) {
                    PassOutcome.INFEASIBLE -> {
                        fired.add(pass)
                        infeasible = true
                    }

                    PassOutcome.CHANGED -> {
                        fired.add(pass)
                        version++
                    }

                    PassOutcome.UNCHANGED -> if (pass.skipAfterEmpty) exhausted.add(pass)
                }
                if (infeasible) break
                if (pass.timing != PresolveTiming.FAST) {
                    unitsAfterRun[pass] = host.changedUnits()
                    sizeAtRun[pass] = size
                }
                host.afterPass(pass)
            }
            if (infeasible) break
            if (!ranAny) break
            round++
            val roundEndComplexity = host.complexity()
            val reduced = roundStartComplexity - roundEndComplexity
            if (reduced > 0 && reduced.toDouble() < PRESOLVE_ABORT_FRACTION * roundStartComplexity) break
            roundStartComplexity = roundEndComplexity
        }
        return Result(fired.toList(), infeasible)
    }

    /** Compose pass reconstruction functions in reverse application order. */
    fun compose(reconstructs: List<(Sample) -> Sample>): (Sample) -> Sample = if (reconstructs.isEmpty()) {
        { it }
    } else {
        { sample -> reconstructs.foldRight(sample) { f, acc -> f(acc) } }
    }

    private fun sliceOf(budget: PresolveBudget, cancellation: Cancellation, eligible: Int): Cancellation {
        val left = budget.remaining()
        val share = if (eligible > 1) left / 2 else left
        return budget.slice(share) or cancellation
    }
}
