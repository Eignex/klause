package com.eignex.klause.propagation

import com.eignex.klause.ir.IntDomain

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
 * [v]'s domain at undo-log position [atTrail], up to interior carves made since: the snapshot the first of its
 * logged moves at or after that position saved, else its live domain. Only interior carves are journalled
 * without a snapshot, so the bounds are exact and a hole is the hole it was unless [carvedAt] places it later.
 */
internal fun PropagationState.domainAt(v: Int, atTrail: Int): IntDomain {
    val moves = boundMoves[v] ?: return intDomains[v]
    var lo = 0
    var hi = moves.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (moves[mid] >= atTrail) hi = mid else lo = mid + 1
    }
    return if (lo < moves.size) requireNotNull(undo.domain[moves[lo]]) else intDomains[v]
}

/** [v]'s lower ([lower]) or upper bound as it stood at undo-log position [atTrail]. */
internal fun PropagationState.boundAt(v: Int, lower: Boolean, atTrail: Int): Long {
    val d = domainAt(v, atTrail)
    return if (lower) d.min else d.max
}
