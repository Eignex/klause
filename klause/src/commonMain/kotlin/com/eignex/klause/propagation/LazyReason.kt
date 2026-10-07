package com.eignex.klause.propagation

// Lazy reasons. A deduction may record, in place of its literal array, a marker array naming the propagator,
// the trail position and level it was made at, and a propagator-specific payload. Conflict analysis reads every
// reason through [reasonOf], which asks the propagator to build that one ([Propagator.explain]) only when the
// analysis resolves through it, so a reason that costs O(arity) to build costs nothing on deductions no conflict
// reaches. Layout: [LAZY_MARKER, factorId, trail position, level, payload...]. Literals are never negative, so
// the marker cannot be mistaken for one.

private const val LAZY_MARKER = -2
private const val HEADER = 4

/** Whether [reason] is a lazy marker rather than literals. */
internal fun isLazyReason(reason: IntArray?): Boolean = reason != null && reason.isNotEmpty() && reason[0] == LAZY_MARKER

/** A lazy reason for a deduction the current factor makes now, carrying [payload] for its [Propagator.explain]. */
internal fun PropagationState.lazyReason(payload: IntArray): IntArray {
    val out = IntArray(HEADER + payload.size)
    out[0] = LAZY_MARKER
    out[1] = currentFactor
    out[2] = undo.size
    out[3] = currentLevel
    payload.copyInto(out, HEADER)
    return out
}

/**
 * The literals of [reason]: the array itself when it is eager, else the reason its propagator builds for the
 * deduction it recorded. Built once per lazy marker and kept in [PropagationState.lazyReasonMemo], since the
 * bounds it is built from stay as they were for as long as the deduction stands.
 */
internal fun PropagationState.reasonOf(reason: IntArray?): IntArray? {
    if (!isLazyReason(reason)) return reason
    val marker = requireNotNull(reason)
    if (lazyReasonMemo.containsKey(marker)) return lazyReasonMemo[marker]
    val fid = marker[1]
    val built = factorAt(fid).explain(this, fid, marker.copyOfRange(HEADER, marker.size), marker[2], marker[3])
    check(!isLazyReason(built)) { "a lazy reason must explain itself in literals" }
    lazyReasonMemo[marker] = built
    return built
}

/**
 * [v]'s lower ([lower]) or upper bound as it stood at undo-log position [atTrail]: the bound before the first of
 * its moves logged at or after that position, else its live bound.
 */
internal fun PropagationState.boundAt(v: Int, lower: Boolean, atTrail: Int): Long {
    val moves = boundMoves[v]
    if (moves != null) {
        var lo = 0
        var hi = moves.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (moves[mid] >= atTrail) hi = mid else lo = mid + 1
        }
        if (lo < moves.size) {
            val prior = requireNotNull(undo.domain[moves[lo]])
            return if (lower) prior.min else prior.max
        }
    }
    val d = intDomains[v]
    return if (lower) d.min else d.max
}
