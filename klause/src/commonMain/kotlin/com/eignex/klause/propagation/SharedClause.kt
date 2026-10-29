package com.eignex.klause.propagation

/**
 * A learned CDCL nogood in a **session-portable** form, for sharing across backtrack arms of one
 * `Problem` (a parallel or single-threaded portfolio). All arms share the
 * same problem, so a nogood any arm learns is logically valid for every arm — but klause is an LCG
 * solver whose int-bound *atoms* are allocated lazily per session, so a clause's atom ids differ
 * between sessions and cannot be copied raw. This form keeps the two literal kinds separately:
 *
 *  - [boolLits] — literals over real boolean variables (id `< numBoolVars`), [com.eignex.klause.ir.Lit]-encoded.
 *    Boolean var ids are stable across sessions of the same problem, so these travel as-is.
 *  - [atomQuads] — int-bound atom literals, flattened as `[intVar, kind, threshold, sign]` per atom
 *    (`kind` 0 = `≥`, 1 = `≤`, 2 = `=`; `sign` 0 = positive). Stored as `Long` because the atom
 *    threshold is a domain value that may exceed 32-bit range; `intVar` / `kind` / `sign` are small
 *    non-negative fields that fit `Long` losslessly. The importing session re-allocates each atom in
 *    its own space ([PropagationSession.importClause]).
 *
 * Built by [PropagationSession.exportGlueClauses] and consumed by [PropagationSession.importClause].
 */
class SharedClause internal constructor(
    internal val boolLits: IntArray,
    internal val atomQuads: LongArray,
    internal val lbd: Int,
    internal val key: Long = contentKey(LongArray(boolLits.size + atomQuads.size / QUAD) { i ->
        if (i < boolLits.size) boolLits[i].toLong() else {
            val q = (i - boolLits.size) * QUAD
            atomToken(atomQuads[q], atomQuads[q + 1], atomQuads[q + 2], atomQuads[q + 3])
        }
    }),
) {
    internal val payloadBytes: Long
        get() = boolLits.size.toLong() * Int.SIZE_BYTES + atomQuads.size.toLong() * Long.SIZE_BYTES

    internal companion object {
        const val QUAD = 4
        private const val SEED = 1_125_899_906_842_597L
        private const val MULT = 31L

        fun atomToken(variable: Long, kind: Long, threshold: Long, sign: Long): Long =
            (variable shl 40) xor (kind shl 36) xor (threshold shl 4) xor sign

        /** Order-independent sharing identity; collisions can decline an optional share only. */
        fun contentKey(tokens: LongArray, size: Int = tokens.size): Long {
            tokens.sort(0, size)
            var hash = SEED
            for (i in 0 until size) hash = hash * MULT + tokens[i]
            return hash
        }
    }
}

/**
 * Engine hook the backtrack solver invokes at each restart boundary (decision level 0, where an
 * imported clause's literals are not all-false so it can be registered safely). A portfolio supplies
 * an implementation that imports pending shared nogoods into the session and exports the session's
 * new glue clauses to the shared pool. `null` on [com.eignex.klause.backtrack.BacktrackParams.clauseExchange]
 * (the default) means no sharing — a standalone solve is unaffected.
 */
interface ClauseExchange {
    /** Incremental import + export at a level-0 restart of a *continuous* search: import the nogoods
     *  published since this arm last looked and export its new glue. The session persists across the
     *  restart, so clauses the arm already holds are not re-imported. */
    fun onRestart(session: PropagationSession)

    /** Import at the post-seed root before traversal. A fresh session needs the whole current pool,
     *  including its own clauses from earlier sessions. Rebinding assumptions on a retained session
     *  keeps its learned store, so the exchange preserves that session's duplicate suppression.
     *  Default no-op for non-pooled exchanges. */
    fun onSearchStart(session: PropagationSession) {}

    /** Export-only hook fired when a search ends without a clean restart boundary — a slice-truncated
     *  ([com.eignex.klause.backtrack.BacktrackParams.cancellation]) segment of a single-threaded
     *  portfolio. The trail may not be at root, so this must not import (an imported clause's literals
     *  could be all-false mid-search); exporting the learned DB is level-independent. Default no-op for
     *  exchanges that publish only at restarts. */
    fun onSearchEnd(session: PropagationSession) {}

    /** Release worker-private exchange state when the owning search is retired, after its final use drain.
     *  Suspension uses [onSearchEnd] and retains that state for resumption. */
    fun onSearchClose(session: PropagationSession) {}

    /** Publish a globally-valid nogood (an LP Farkas infeasibility certificate) to the shared pool
     *  directly, bypassing the LBD glue filter: it holds for every arm of the same problem regardless
     *  of its LBD, but is too long / high-LBD to pass the glue export. Default no-op. */
    fun publishGlobal(clause: SharedClause) {}
}
