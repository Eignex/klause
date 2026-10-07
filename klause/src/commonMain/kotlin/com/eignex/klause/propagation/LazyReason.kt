package com.eignex.klause.propagation

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit

// Lazy reasons. A deduction may record, in place of its literal array, a marker array naming the propagator,
// the trail position and level it was made at, and a propagator-specific payload. Conflict analysis reads every
// reason through [reasonOf], which asks the propagator to build that one ([Propagator.explain]) only when the
// analysis resolves through it, so a reason that costs O(arity) to build costs nothing on deductions no conflict
// reaches. Layout: [LAZY_MARKER, factorId, trail position, level, payload...]. Literals are never negative, so
// the marker cannot be mistaken for one.

private const val LAZY_MARKER = -2
private const val HEADER = 4

/** Where a lazy reason's payload starts in the array [lazyReasonSlots] returns. */
internal const val LAZY_PAYLOAD = HEADER

// The factor slot of a lazy reason extended by literals ([extendReason]): no propagator builds it; it is its
// base, embedded after the literals, plus those literals.
private const val EXTENDED = -1

/** Whether [reason] is a lazy marker rather than literals. */
internal fun isLazyReason(reason: IntArray?): Boolean =
    reason != null && reason.isNotEmpty() && reason[0] == LAZY_MARKER

/**
 * A lazy reason for a deduction factor [factorId] (by default the one propagating) makes now, carrying [payload]
 * for its [Propagator.explain].
 */
internal fun PropagationState.lazyReason(payload: IntArray, factorId: Int = currentFactor): IntArray {
    val out = lazyReasonSlots(payload.size, factorId)
    payload.copyInto(out, LAZY_PAYLOAD)
    return out
}

/**
 * [lazyReason] with [size] payload slots, from [LAZY_PAYLOAD], for the caller to fill in place: a deduction made on
 * every propagation then allocates its marker alone, not a payload copied into it.
 */
internal fun PropagationState.lazyReasonSlots(size: Int, factorId: Int = currentFactor): IntArray {
    check(factorId >= 0) { "a lazy reason needs the factor that explains it" }
    val out = IntArray(HEADER + size)
    out[0] = LAZY_MARKER
    out[1] = factorId
    out[2] = undo.size
    out[3] = currentLevel
    return out
}

/**
 * [base] with [extra] literals added. A lazy [base] stays unbuilt: the result is a lazy reason that embeds it,
 * so a bound move that crossed holes or cites its prior bound costs no reason-building on the propagation path.
 */
internal fun extendReason(base: IntArray?, extra: IntArray): IntArray? {
    if (extra.isEmpty()) return base
    if (base == null) return extra
    if (!isLazyReason(base)) return base + extra
    val out = IntArray(HEADER + 1 + extra.size + base.size)
    out[0] = LAZY_MARKER
    out[1] = EXTENDED
    out[HEADER] = extra.size
    extra.copyInto(out, HEADER + 1)
    base.copyInto(out, HEADER + 1 + extra.size)
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
    val built = if (fid == EXTENDED) {
        val count = marker[HEADER]
        val extra = marker.copyOfRange(HEADER + 1, HEADER + 1 + count)
        val base = reasonOf(marker.copyOfRange(HEADER + 1 + count, marker.size))
        if (base == null) extra else base + extra
    } else {
        // A lazily recorded deduction is never a decision, so a reason with no literals (every premise a root
        // fact) is an empty clause body, not the null that marks a decision.
        factorAt(fid).explain(this, fid, marker.copyOfRange(HEADER, marker.size), marker[2], marker[3]) ?: IntArray(0)
    }
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

/** Whether `k` was in [v]'s domain at undo-log position [atTrail]. */
internal fun PropagationState.inDomainAt(v: Int, k: Long, atTrail: Int): Boolean {
    val d = domainAt(v, atTrail)
    return k in d || (k in d.min..d.max && carvedAt(v, k) > atTrail)
}

/**
 * The literal, false at undo-log position [atTrail], that says `k` was out of [v]'s domain then: the bound it lay
 * past, or its own carve. [Lit.NONE] when nothing on the path removed it: a root hole or a survivor restriction,
 * both unconditional. Only for a `k` that was out then (not [inDomainAt]); [d] is [v]'s domain then, for a caller
 * that already read it.
 */
internal fun PropagationState.exclusionLiteral(
    v: Int,
    k: Long,
    atTrail: Int,
    d: IntDomain = domainAt(v, atTrail),
): Int {
    val root = rootDomains[v]
    return when {
        k !in root -> Lit.NONE
        k < d.min -> Lit.make(atomVarGe(v, d.min), false)
        k > d.max -> Lit.make(atomVarLe(v, d.max), false)
        carvedAt(v, k) >= 0 -> Lit.make(atomVarEq(v, k), true)
        else -> Lit.NONE
    }
}

/** Whether Boolean [v] was already pinned at undo-log position [atTrail]. */
internal fun PropagationState.boolPinnedAt(v: Int, atTrail: Int): Boolean =
    boolValues[v] != null && (atTrail >= undo.size || boolPinPos[v] < atTrail)

/**
 * The literal, false at undo-log position [atTrail], saying [v]'s lower ([lower]) or upper bound reached [need]:
 * [need] itself where the bound may be cited weaker than it stood, else the bound then. [Lit.NONE] when the root
 * domain already guarantees [need]. A bound established below [atLevel] lifts freely, since only the learned
 * clause ever cites it; one at [atLevel] lifts only when it is that level's decision, the one literal there with
 * no reason to resolve.
 */
internal fun PropagationState.boundLiteral(v: Int, lower: Boolean, need: Long, atTrail: Int, atLevel: Int): Int {
    val root = rootDomains[v]
    if (if (lower) need <= root.min else need >= root.max) return Lit.NONE
    val d = domainAt(v, atTrail)
    val bound = if (lower) d.min else d.max
    check(if (lower) need <= bound else need >= bound) { "a bound literal must hold when cited" }
    val cite = if (need != bound && liftableAt(v, lower, bound, atLevel)) need else bound
    return if (lower) Lit.make(atomVarGe(v, cite), false) else Lit.make(atomVarLe(v, cite), false)
}

// Only a level decided on [v] itself can make the move a decision, so only then is the move's reason built.
private fun PropagationState.liftableAt(v: Int, lower: Boolean, bound: Long, atLevel: Int): Boolean {
    val level = boundEstablishmentLevel(v, bound, lower) ?: return false
    if (level < atLevel) return true
    val decisions = levelToDecisionVar
    return level in 1..decisions.size && decisions[level - 1] == problem.numBoolVars + v &&
        boundEstablishment(v, bound, lower)?.reason == null
}
