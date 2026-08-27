package com.eignex.klause.presolve

import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation

/** Abort a pass schedule after a round makes only a marginal complexity reduction. */
private const val PRESOLVE_ABORT_FRACTION = 0.001

/** Shared bounded-fixpoint scheduler for fresh and incremental presolve hosts. */
internal object PresolveRoundEngine {

    /** The changes made by one bounded pass schedule. */
    class Result(val fired: List<PresolvePass>, val reconstructs: List<(Sample) -> Sample>)

    /** Drive [passes] to their bounded fixpoint through the supplied host operations. */
    fun run(
        passes: List<PresolvePass>,
        maxRounds: Int,
        cancellation: Cancellation,
        budget: PresolveBudget?,
        passInput: () -> Problem,
        passContext: (PresolvePass) -> PresolveContext,
        applyDelta: (PassDelta) -> Unit,
        afterPass: (PresolvePass) -> Unit,
        complexity: () -> Long,
    ): Result {
        val reconstructs = ArrayList<(Sample) -> Sample>()
        var version = 0
        val ranAtVersion = HashMap<PresolvePass, Int>()
        val fired = LinkedHashSet<PresolvePass>()
        val exhausted = HashSet<PresolvePass>()
        var round = 0
        var roundStartComplexity = complexity()
        while (round < maxRounds && !cancellation()) {
            var ranAny = false
            var eligible = passes.count { it !in exhausted && ranAtVersion[it] != version }
            for (pass in passes) {
                if (cancellation()) break
                if (pass in exhausted || ranAtVersion[pass] == version) continue
                ranAtVersion[pass] = version
                ranAny = true
                val input = passInput()
                val ctx = passContext(pass)
                val sliced = budget?.let { ctx.withCancellation(sliceOf(it, cancellation, eligible)) } ?: ctx
                eligible--
                val delta = pass.apply(input, sliced)
                if (!delta.isEmpty) {
                    delta.reconstruct?.let(reconstructs::add)
                    applyDelta(delta)
                    fired.add(pass)
                    version++
                } else if (pass.skipAfterEmpty) {
                    exhausted.add(pass)
                }
                afterPass(pass)
            }
            if (!ranAny) break
            round++
            val reduced = roundStartComplexity - complexity()
            if (reduced > 0 && reduced.toDouble() < PRESOLVE_ABORT_FRACTION * roundStartComplexity) break
            roundStartComplexity = complexity()
        }
        return Result(fired.toList(), reconstructs)
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
