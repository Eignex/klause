package com.eignex.klause.propagation

/**
 * A learned nogood in a session-portable form, for sharing across arms of one `Problem`. Order-literal atoms are
 * allocated lazily per session, so their ids differ between sessions: Boolean literals travel as-is and each atom
 * travels as `(intVar, kind, threshold, sign)`, re-allocated by [PropagationSession.importClause].
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

        fun contentKey(tokens: LongArray, size: Int = tokens.size): Long {
            tokens.sort(0, size)
            var hash = SEED
            for (i in 0 until size) hash = hash * MULT + tokens[i]
            return hash
        }
    }
}

/**
 * Clause sharing at restart boundaries, where an imported clause's literals are not all false. `null` on
 * [com.eignex.klause.backtrack.BacktrackParams.clauseExchange] means no sharing.
 */
interface ClauseExchange {
    /** Import nogoods published since this arm last looked and export its new glue, at a level-0 restart. */
    fun onRestart(session: PropagationSession)

    /**
     * Import at the post-seed root before traversal. A fresh session needs the whole current pool, including its
     * own clauses from earlier sessions; a retained session keeps its learned store and duplicate suppression.
     */
    fun onSearchStart(session: PropagationSession) {}

    /**
     * Export-only hook for a search that ends off a restart boundary, such as a
     * [com.eignex.klause.backtrack.BacktrackParams.cancellation] slice. The trail may not be at root, so it must
     * not import: an imported clause's literals could all be false mid-search.
     */
    fun onSearchEnd(session: PropagationSession) {}

    /** Observe retained state at a cooperative pause without exchanging clauses or draining use credit. */
    fun onSearchPause(session: PropagationSession) {}

    /** Release worker-private exchange state when the owning search is retired, after its final use drain.
     *  [onSearchEnd] and [onSearchPause] retain that state for resumption. */
    fun onSearchClose(session: PropagationSession) {}

    /** Publish a globally valid nogood, such as an LP Farkas certificate, bypassing the LBD glue filter. */
    fun publishGlobal(clause: SharedClause) {}
}
