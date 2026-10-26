package com.eignex.klause.localsearch

import com.eignex.klause.solver.Sample

/** Owned diagnostic values from one restored [LocalSearchState.inspectMove] probe. */
class LocalSearchMoveInspection internal constructor(
    /** Candidate assignment, including maintained outputs. */
    val assignment: Sample,
    /** Candidate factor grades in the state's factor order. */
    val factorDegrees: IntArray,
    /** Unweighted hard-cost change. */
    val netDelta: Long,
    /** Hard-cost change under already allocated weights, or unit weights otherwise. */
    val weightedNetDelta: Double,
    /** Change in the explicitly supplied heuristic objective. */
    val objectiveDelta: Double,
)
