package com.eignex.klause.lp.cut

import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.relaxation.LpRelaxation

/** A globally justified source inequality exchanged by workers of the same source model. */
class SharedCut internal constructor(internal val source: SourceCut) {
    init { require(source.provenance.global) }

    internal val key: Long = source.key.fold(source.provenance.model.hashCode().toLong()) { hash, c ->
        hash * 0x100000001b3L + c.code
    }

    internal fun toCut(relaxation: LpRelaxation): Cut? {
        val map = relaxation.sourceMap ?: return null
        return source.toCut(map).orNull()?.takeIf { it.global }
    }

    internal companion object {
        fun fromCut(cut: Cut, relaxation: LpRelaxation): SharedCut? {
            val source = SourceCut.fromCut(cut, relaxation).orNull() ?: return null
            return if (source.provenance.global) SharedCut(source) else null
        }
    }
}

/**
 * A worker's cut-sharing surface for a [CutExchange] — the cut analogue of the public
 * [com.eignex.klause.propagation.PropagationSession] methods a
 * [com.eignex.klause.propagation.ClauseExchange] uses. The engine owning the local cut pool and
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
 * is [com.eignex.klause.portfolio.PoolCutExchange]; the portfolio wiring decides when [exchange]
 * fires (the restart boundary). Importing only ever adds globally-valid cuts, so it is sound regardless
 * of the worker's current search node.
 */
interface CutExchange {
    /** Import the peers' cuts into [sharing] and publish [sharing]'s exported cuts to the store. */
    fun exchange(sharing: CutSharing)
}
