package com.eignex.klause.formats.flatzinc

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.PreparedSourceInvocation
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.result.LpStats

/** Attach original source declarations and construction hints. */
fun FlatZincProgram.sourceAttachment(): SourceAttachment =
    SourceAttachment(problem, definitionHints, searchHints?.toSearchHints())

/** Prepare execution over the original source. */
fun FlatZincProgram.prepareSourceExecution(request: SourceSetupRequest): PreparedSourceInvocation {
    require(request.source.originalSource === problem) { "FlatZinc request belongs to another source" }
    return SourceSetup.prepare(request)
}

/** Prepare original execution while observing bound-probing LP work. */
fun FlatZincProgram.prepareSourceExecution(
    request: SourceSetupRequest,
    onLpStats: (LpStats) -> Unit,
): PreparedSourceInvocation {
    require(request.source.originalSource === problem) { "FlatZinc request belongs to another source" }
    return SourceSetup.prepare(request, onLpStats)
}

internal fun FlatZincProgram.forSample(sample: Sample): FlatZincProgram {
    require(sample.coordinateFrame == null || sample.coordinateFrame === problem) {
        "FlatZinc sample belongs to a different coordinate frame"
    }
    return this
}
