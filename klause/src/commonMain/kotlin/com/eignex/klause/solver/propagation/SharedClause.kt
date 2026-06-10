package com.eignex.klause.solver.propagation

/**
 * A learned CDCL nogood in a **session-portable** form, for sharing across backtrack arms of one
 * [com.eignex.klause.solver.Problem] (a parallel or single-threaded portfolio). All arms share the
 * same problem, so a nogood any arm learns is logically valid for every arm — but klause is an LCG
 * solver whose int-bound *atoms* are allocated lazily per session, so a clause's atom ids differ
 * between sessions and cannot be copied raw. This form keeps the two literal kinds separately:
 *
 *  - [boolLits] — literals over real boolean variables (id `< numBoolVars`), [com.eignex.klause.solver.Lit]-encoded.
 *    Boolean var ids are stable across sessions of the same problem, so these travel as-is.
 *  - [atomQuads] — int-bound atom literals, flattened as `[intVar, kind, threshold, sign]` per atom
 *    (`kind` 0 = `≥`, 1 = `≤`, 2 = `=`; `sign` 0 = positive). The importing session re-allocates each
 *    atom in its own space ([PropagationSession.importClause]).
 *
 * Built by [PropagationSession.exportGlueClauses] and consumed by [PropagationSession.importClause].
 */
class SharedClause internal constructor(
    internal val boolLits: IntArray,
    internal val atomQuads: IntArray,
    internal val lbd: Int,
) {
    /** Order-independent content key for pool de-duplication. A hash collision only drops a *share*
     *  (the clause is simply not propagated to one arm); it is never unsound. */
    internal val key: Long = run {
        val tokens = ArrayList<Long>(boolLits.size + atomQuads.size / QUAD)
        for (l in boolLits) tokens.add(l.toLong())
        var i = 0
        while (i < atomQuads.size) {
            // Pack a quad into one token; bit layout is arbitrary, only equality/order matters.
            val t = (atomQuads[i].toLong() shl 40) xor (atomQuads[i + 1].toLong() shl 36) xor
                (atomQuads[i + 2].toLong() shl 4) xor atomQuads[i + 3].toLong()
            tokens.add(t)
            i += QUAD
        }
        tokens.sort()
        var h = SEED
        for (t in tokens) h = h * MULT + t
        h
    }

    internal companion object {
        const val QUAD = 4
        private const val SEED = 1_125_899_906_842_597L
        private const val MULT = 31L
    }
}

/**
 * Engine hook the backtrack solver invokes at each restart boundary (decision level 0, where an
 * imported clause's literals are not all-false so it can be registered safely). A portfolio supplies
 * an implementation that imports pending shared nogoods into the session and exports the session's
 * new glue clauses to the shared pool. `null` on [com.eignex.klause.solver.backtrack.BacktrackParams]
 * (the default) means no sharing — a standalone solve is unaffected.
 */
interface ClauseExchange {
    /** Import + export at a level-0 restart of [session]. Called once per restart. */
    fun onRestart(session: PropagationSession)
}
