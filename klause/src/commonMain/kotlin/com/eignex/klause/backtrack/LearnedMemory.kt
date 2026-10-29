package com.eignex.klause.backtrack

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.result.SearchStatsSink

internal fun maintainLearnedMemory(
    session: PropagationSession,
    params: BacktrackParams,
    inprocessing: Inprocessing?,
    stats: SearchStatsSink?,
) {
    val limit = params.learnedMemoryBytes ?: return
    val result = session.reclaimLearnedMemory(limit, params.learnedMemoryWork, params.learnedMemoryScratchBytes,
        { inprocessing?.releaseRetainedBuffers() ?: false })
    stats?.noteInprocessing("learnedMemory${result.status.name}", 1L)
    stats?.noteInprocessing("learnedMemoryWork", result.work)
    if (result.protectedLimitExceeded) stats?.noteInprocessing("learnedMemoryProtectedLimit", 1L)
}
