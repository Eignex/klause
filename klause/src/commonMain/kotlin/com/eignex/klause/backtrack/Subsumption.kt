package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Lit
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

/**
 * Clause subsumption and self-subsuming resolution over the live learned database (#1252). Walks a
 * bounded round-robin slice ([BacktrackParams.subsumeBatch]) of the learned clauses: a clause with a
 * subset among the other learned clauses is dropped, and a clause whose subset match holds with
 * exactly one literal negated is strengthened by resolving that literal away. Runs on both clause
 * stores — the general database and the native-SAT arena's learned buffer — over pure-Boolean,
 * non-permanent clauses.
 *
 * Soundness is direction-free: every participating clause is learned, hence implied by the base
 * formula, so dropping one never loses models and a self-subsuming resolvent (implied by the two
 * clauses it resolves) is itself implied. Order among same-batch drops therefore never matters; the
 * pass only trades database bytes for propagation strength.
 *
 * The occurrence index registers each clause under a single literal: a subsuming subset must contain
 * that literal (possibly negated, for the resolution case), so probing both polarities of the
 * candidate's literals finds every match while keeping the probe cost proportional to the occurrence
 * lists actually touched. The index is rebuilt per run from a snapshot; like [vivify], any mutation
 * renumbers the database, so the cursor restarts after a mutating pass.
 */
internal fun BacktrackSolver.subsume(session: PropagationSession, params: BacktrackParams, startCursor: Int): Int {
    val count = session.learnedClauseCount
    if (count < 2) return 0
    val native = session.usesNativeSat
    val numBool = session.problem.numBoolVars
    // Snapshot: sorted literal arrays + 64-bit literal signatures for the participating clauses.
    val lits = arrayOfNulls<IntArray>(count)
    val sigs = LongArray(count)
    for (i in 0 until count) {
        if (session.learnedClausePermanent(i)) continue
        if (!session.isLearnedClause(i)) continue // pseudo-Boolean nogoods don't participate (#1119)
        if (!native && !session.learnedClauseAt(i).allLiteralsBool(numBool)) continue
        val l = session.learnedClauseLiterals(i).copyOf()
        if (l.size < 2) continue
        l.sort()
        lits[i] = l
        sigs[i] = signatureOf(l)
    }
    // One-literal registration: clause i is discoverable through occ of its smallest literal.
    val occ = HashMap<Int, IntArrayList>()
    for (i in 0 until count) {
        val l = lits[i] ?: continue
        occ.getOrPut(l[0]) { IntArrayList() }.add(i)
    }
    val dropIdx = IntHashSet()
    val replacements = ArrayList<IntArray>()
    val replacementLbds = IntArrayList()
    val batch = params.subsumeBatch.coerceAtLeast(1)
    var cursor = if (startCursor in 0 until count) startCursor else 0
    var examined = 0
    while (examined < batch && examined < count) {
        val idx = cursor
        cursor = (cursor + 1) % count
        examined++
        val c = lits[idx] ?: continue
        if (idx in dropIdx) continue
        var strengthened: IntArray? = null
        probe@ for (lit in c) {
            for (polarity in 0..1) {
                val key = if (polarity == 0) lit else Lit.negate(lit)
                val list = occ[key] ?: continue
                for (k in 0 until list.size) {
                    val j = list[k]
                    if (j == idx || j in dropIdx) continue
                    val d = lits[j] ?: continue
                    if (d.size > c.size || (sigs[j] and sigs[idx].inv()) != 0L) continue
                    val flipped = subsetWithOneFlip(d, c) ?: continue
                    if (flipped == NO_FLIP) {
                        dropIdx.add(idx)
                        break@probe
                    }
                    if (strengthened == null && c.size > 2) {
                        strengthened = removeLiteral(c, flipped)
                    }
                }
            }
        }
        if (idx !in dropIdx && strengthened != null) {
            dropIdx.add(idx)
            replacements.add(strengthened)
            // The resolvent is at least as strong as the clause it replaces: inherit the parent's LBD
            // (capped by the new size) so the derived clause keeps its tier and glue-export standing.
            replacementLbds.add(minOf(session.learnedClauseLbd(idx), strengthened.size))
        }
    }
    if (dropIdx.isEmpty()) return cursor
    session.forgetLearnedClauses { i, _ -> i !in dropIdx }
    for (r in replacements.indices) session.addLearnedClause(Clause(replacements[r]), lbd = replacementLbds[r])
    // The forget renumbered the database, so resume the round-robin from the start.
    return 0
}

/** Sentinel for [subsetWithOneFlip]: a clean subset, no literal flipped. */
private const val NO_FLIP = -1

/**
 * When every literal of sorted [d] occurs in sorted [c] directly, returns [NO_FLIP]; when exactly one
 * occurs negated instead (the self-subsuming resolution case), returns that negated literal as it
 * appears in [c]; otherwise null.
 */
private fun subsetWithOneFlip(d: IntArray, c: IntArray): Int? {
    var flipped = NO_FLIP
    for (lit in d) {
        if (binaryContains(c, lit)) continue
        val neg = Lit.negate(lit)
        if (flipped == NO_FLIP && binaryContains(c, neg)) {
            flipped = neg
            continue
        }
        return null
    }
    return flipped
}

private fun binaryContains(sorted: IntArray, value: Int): Boolean {
    var lo = 0
    var hi = sorted.size - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val v = sorted[mid]
        when {
            v < value -> lo = mid + 1
            v > value -> hi = mid - 1
            else -> return true
        }
    }
    return false
}

private fun removeLiteral(c: IntArray, lit: Int): IntArray {
    val out = IntArray(c.size - 1)
    var j = 0
    for (l in c) if (l != lit) out[j++] = l
    return out
}

/** 64-bit variable signature: a set bit per variable bucket, so `sig(d) and sig(c).inv() != 0`
 *  refutes both the clean subset and the one-flip case (a flip changes polarity, not the variable)
 *  without touching the arrays. */
private fun signatureOf(sorted: IntArray): Long {
    var sig = 0L
    for (lit in sorted) sig = sig or (1L shl (Lit.variable(lit) * SIG_MIX ushr SIG_SHIFT))
    return sig
}

private const val SIG_MIX = 0x9E3779B1.toInt()
private const val SIG_SHIFT = 26
