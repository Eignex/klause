package com.eignex.klause.backtrack

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.result.SearchStatsSink

internal class LearnedMemoryMaintenance {
    private data class Position(
        val owner: Any,
        val count: Int,
        val revision: Long,
        val level: Int,
        val undo: Int,
        val atoms: Int,
        val ownershipWork: Long,
        val sharingBytes: Long,
        val inprocessingWork: Long,
        val publication: Boolean,
    )

    private var observed: Position? = null
    fun reset() { observed = null }

    fun run(
        session: PropagationSession,
        params: BacktrackParams,
        inprocessing: Inprocessing?,
        stats: SearchStatsSink?,
    ) {
        val limit = params.learnedMemoryBytes ?: return
        fun position() = Position(session.sharingIdentity, session.learnedClauseCount, session.learnedClauseRevision,
            session.decisionLevel, session.explanationState.undoTop, session.explanationState.atoms.intVar.size,
            session.ownershipWork, session.retainedSharingSize?.invoke() ?: 0L,
            inprocessing?.retainedRevision ?: 0L, inprocessing?.retainsPublication == true)
        if (observed == position()) return
        val result = session.reclaimLearnedMemory(limit, params.learnedMemoryWork, params.learnedMemoryScratchBytes,
            { inprocessing?.releaseRetainedBuffers() ?: false }, inprocessing?.retainsPublication == true)
        // Repeating unchanged maintenance must not consume every short resumed slice before a branch.
        observed = position().takeUnless {
            result.status == com.eignex.klause.propagation.ReclamationStatus.CANCELLED
        }
        stats?.noteInprocessing("learnedMemory${result.status.name}", 1L)
        stats?.noteInprocessing("learnedMemoryWork", result.work)
        if (result.protectedLimitExceeded) stats?.noteInprocessing("learnedMemoryProtectedLimit", 1L)
    }
}
