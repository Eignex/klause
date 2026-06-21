package com.eignex.klause.solver.lp.cut

import com.eignex.klause.solver.lp.Relation
import com.eignex.klause.solver.lp.relaxation.LpRelaxation

/**
 * A portfolio-portable [Cut], encoded over **CP variables** rather than a relaxation's structural LP
 * columns. A [Cut] stores column indices, which are private to the relaxation that produced it — column
 * `k` means different variables in different workers' relaxations — so a cut cannot be shared as-is. A
 * [SharedCut] instead names each term by its CP variable (`(varId, isBool)`), exactly as [com.eignex
 * .klause.solver.propagation.SharedClause] names bound atoms by `(intVar, threshold)`, so it round-trips
 * across the workers of one [com.eignex.klause.solver.Problem]: [fromCut] reads a cut's columns back to
 * CP variables via the source relaxation's [LpRelaxation.colVarId] / [LpRelaxation.colIsBool], and
 * [toCut] re-maps them to the importing relaxation's columns via [LpRelaxation.intColOf] /
 * [LpRelaxation.boolColOf].
 *
 * Only globally-valid cuts are ever shared (the [CutPool] holds only [Cut.global] cuts), so an imported
 * cut is valid for every solution of the Problem regardless of the node it was derived at — importing it
 * can only tighten the relaxation, never remove a feasible point. The content key is an order-independent
 * hash for de-duplication across workers; a collision merely drops one share (loses strength,
 * never soundness). Constructed internally ([fromCut] or the portfolio pool); the type is public only so
 * it can appear in the public [CutSharing] / [CutExchange] surface, mirroring `SharedClause`.
 */
class SharedCut internal constructor(
    internal val vars: IntArray,
    internal val isBool: BooleanArray,
    internal val coeffs: LongArray,
    internal val rel: Relation,
    internal val rhs: Long,
) {
    /** Worker-independent content key: terms folded in `(varId, isBool)`-sorted order so the same
     *  inequality hashes equally no matter which worker or relaxation produced it. */
    internal val key: Long = run {
        val order = vars.indices.sortedWith(compareBy({ vars[it] }, { isBool[it] }))
        var h = SEED
        for (i in order) {
            h = h * MULT + vars[i]
            h = h * MULT + if (isBool[i]) 1L else 0L
            h = h * MULT + coeffs[i]
        }
        h = h * MULT + rel.ordinal.toLong()
        h = h * MULT + rhs
        h
    }

    /** Re-map this cut onto [relaxation]'s structural columns, or null if any term's variable has no
     *  column there (that worker's relaxation cannot express it). The result is flagged [Cut.global]. */
    internal fun toCut(relaxation: LpRelaxation): Cut? {
        val cols = IntArray(vars.size)
        for (i in vars.indices) {
            val v = vars[i]
            val col = if (isBool[i]) {
                if (v in relaxation.boolColOf.indices) relaxation.boolColOf[v] else -1
            } else {
                if (v in relaxation.intColOf.indices) relaxation.intColOf[v] else -1
            }
            if (col < 0) return null
            cols[i] = col
        }
        return Cut(cols, coeffs.copyOf(), rel, rhs, global = true)
    }

    internal companion object {
        private const val SEED = -0x61c8864680b583ebL // golden-ratio odd seed
        private const val MULT = 0x100000001b3L // FNV-style odd multiplier

        /** Encode [cut] over CP variables using [relaxation]'s column→variable maps, or null if a column
         *  names no CP variable (out of the structural range). */
        fun fromCut(cut: Cut, relaxation: LpRelaxation): SharedCut? {
            val k = cut.cols.size
            val vars = IntArray(k)
            val isBool = BooleanArray(k)
            for (i in 0 until k) {
                val col = cut.cols[i]
                if (col !in relaxation.colVarId.indices) return null
                vars[i] = relaxation.colVarId[col]
                isBool[i] = relaxation.colIsBool[col]
            }
            return SharedCut(vars, isBool, cut.coeffs.copyOf(), cut.rel, cut.rhs)
        }
    }
}

/**
 * A worker's cut-sharing surface for a [CutExchange] — the cut analogue of the public
 * [com.eignex.klause.solver.propagation.PropagationSession] methods a
 * [com.eignex.klause.solver.propagation.ClauseExchange] uses. The engine owning the local cut pool and
 * relaxation implements it; the exchange only ever sees portable [SharedCut]s, never the internal pool
 * or relaxation.
 */
interface CutSharing {
    /** This worker's globally-valid cuts, in portable form. */
    fun exportGlobalCuts(): List<SharedCut>

    /** Fold [cuts] published by other workers into this worker's local pool (skipping any whose
     *  variables it has no column for). */
    fun importCuts(cuts: List<SharedCut>)
}

/**
 * Exchanges globally-valid cuts between a worker and a cross-worker store via the worker's [CutSharing]
 * view: import the cuts other workers published and export this worker's own. The pooled implementation
 * is [com.eignex.klause.portfolio.PoolCutExchange]; the portfolio wiring (#809) decides when [exchange]
 * fires (the restart boundary). Importing only ever adds globally-valid cuts, so it is sound regardless
 * of the worker's current search node.
 */
interface CutExchange {
    /** Import the peers' cuts into [sharing] and publish [sharing]'s exported cuts to the store. */
    fun exchange(sharing: CutSharing)
}
