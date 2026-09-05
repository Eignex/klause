package com.eignex.klause.presolve

import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation

/** Abort a pass schedule after a round makes only a marginal complexity reduction. */
private const val PRESOLVE_ABORT_FRACTION = 0.001

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
    }

    /** The changes made by one bounded pass schedule. */
    class Result(val fired: List<PresolvePass>, val infeasible: Boolean)

    /** Drive [passes] to their bounded fixpoint through [host]. */
    fun run(
        passes: List<PresolvePass>,
        maxRounds: Int,
        cancellation: Cancellation,
        budget: PresolveBudget?,
        host: RoundHost,
    ): Result {
        var version = 0
        val ranAtVersion = HashMap<PresolvePass, Int>()
        val fired = LinkedHashSet<PresolvePass>()
        val exhausted = HashSet<PresolvePass>()
        var infeasible = false
        var round = 0
        var roundStartComplexity = host.complexity()
        while (round < maxRounds && !cancellation()) {
            var ranAny = false
            var eligible = passes.count { it !in exhausted && ranAtVersion[it] != version }
            for (pass in passes) {
                if (cancellation()) break
                if (pass in exhausted || ranAtVersion[pass] == version) continue
                ranAtVersion[pass] = version
                ranAny = true
                val slice = budget?.let { sliceOf(it, cancellation, eligible) }
                eligible--
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
        val slice = budget.slice(share)
        return Cancellation { cancellation() || slice() }
    }
}
