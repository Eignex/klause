package com.eignex.klause.backtrack

import com.eignex.klause.propagation.PropagationSession

/**
 * One scheduled in-search simplification pass over the live constraint database (#1252). Passes run
 * per arm at the restart boundary, after the DFS trail is popped, with the session at the post-seed
 * root; each call works a bounded slice so a single restart never stalls on a full database sweep.
 *
 * Every pass must declare whether it preserves the variable set. The portfolio shares learned
 * clauses across arms under globally stable variable ids, and the native-SAT lane shares its base
 * clause arena read-only across arms — a variable-eliminating pass (BVE) in one arm would break
 * both contracts, so [Inprocessing] rejects such passes at construction. Variable-eliminating
 * simplification runs in presolve, once, before the portfolio fork.
 */
internal interface InprocessingPass {
    /** True when the pass never adds, removes, or renames variables — the portfolio-safe category. */
    val preservesVariables: Boolean

    /** Run one bounded slice with [session] at the post-seed root, leaving it there. */
    fun run(session: PropagationSession, params: BacktrackParams)

    /** Drop per-search cursors; the engine was reseeded onto a new fragment. */
    fun reset()
}

/** Clause vivification (#203) behind the inprocessing seam: a round-robin cursor over the learned
 *  database, one [BacktrackParams.vivifyBatch]-sized slice per scheduled run. */
internal class VivificationPass(private val solver: BacktrackSolver) : InprocessingPass {
    override val preservesVariables: Boolean get() = true
    private var cursor = 0

    override fun run(session: PropagationSession, params: BacktrackParams) {
        cursor = solver.vivify(session, params, cursor)
    }

    override fun reset() {
        cursor = 0
    }
}

/**
 * The scheduled per-arm inprocessing loop (#1252): runs its passes in order at every
 * [BacktrackParams.inprocessingCadence]-th restart. The cadence exists because the passes' probing
 * cost competes with conflict throughput — restart-heavy configurations pay it often — so it is the
 * portfolio's per-arm tuning lever for how much simplification an arm buys.
 */
internal class Inprocessing(private val passes: List<InprocessingPass>, private val cadence: Int) {
    init {
        require(cadence >= 1) { "inprocessing cadence must be >= 1, got $cadence" }
        val eliminating = passes.filter { !it.preservesVariables }
        require(eliminating.isEmpty()) {
            "variable-eliminating passes break the stable-id contract shared clauses rely on: $eliminating"
        }
    }

    private var restartsUntilRun = cadence

    fun onRestart(session: PropagationSession, params: BacktrackParams) {
        if (--restartsUntilRun > 0) return
        restartsUntilRun = cadence
        for (pass in passes) pass.run(session, params)
    }

    fun reset() {
        restartsUntilRun = cadence
        for (pass in passes) pass.reset()
    }

    companion object {
        /**
         * Assemble the loop an engine's [params] ask for, or null when no pass is enabled. Seeded
         * searches get no loop at all: a pass derives clauses by propagating at the current root,
         * and with assumption pins standing those derivations would hold only under the pins yet be
         * stored as unconditional learned clauses.
         */
        fun from(solver: BacktrackSolver, params: BacktrackParams): Inprocessing? {
            if (!params.assumptions.isEmpty) return null
            val passes = buildList {
                if (params.vivification) add(VivificationPass(solver))
            }
            if (passes.isEmpty()) return null
            return Inprocessing(passes, params.inprocessingCadence)
        }
    }
}
