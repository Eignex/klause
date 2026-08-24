package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.propagation.ClauseTier
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

/** Reduce the learned-clause database at a restart without discarding permanent or glue clauses. */
internal fun forgetIfOverCap(session: PropagationSession, params: BacktrackParams) {
    val cap = params.maxLearnedClauses ?: return
    val learnedSize = session.learnedClauseCount
    if (learnedSize <= cap) return
    if (params.tieredLearnedDb) {
        forgetTiered(session, params, cap, learnedSize)
        return
    }
    val glueThreshold = params.lbdGlueThreshold
    val nonGlue = ArrayList<IntArray>(learnedSize)
    for (index in 0 until learnedSize) {
        val lbd = session.learnedClauseLbd(index)
        if (lbd > glueThreshold && !session.learnedClausePermanent(index)) nonGlue += intArrayOf(lbd, index)
    }
    if (nonGlue.isEmpty()) return
    val remaining = (cap - (learnedSize - nonGlue.size)).coerceAtLeast(0)
    if (nonGlue.size <= remaining) return
    nonGlue.sortBy { it[0] }
    val kept = IntHashSet(remaining)
    for (index in 0 until remaining) kept.add(nonGlue[index][1])
    session.forgetLearnedClauses { index, lbd ->
        lbd <= glueThreshold || session.learnedClausePermanent(index) || index in kept
    }
    val dropped = nonGlue.size - remaining
    params.onEvent?.invoke(SearchEvent.LearnedDbSweep(kept = learnedSize - dropped, dropped = dropped))
}

private fun forgetTiered(session: PropagationSession, params: BacktrackParams, cap: Int, learnedSize: Int) {
    val coreThreshold = params.lbdGlueThreshold
    val midThreshold = params.midLbdThreshold
    val locals = ArrayList<IntArray>(learnedSize)
    for (index in 0 until learnedSize) {
        val lbd = session.learnedClauseLbd(index)
        val used = session.learnedClauseUsedSinceReduction(index)
        val tier = session.learnedClauseTier(index).takeUnless { it == ClauseTier.UNSET } ?: when {
            lbd <= coreThreshold -> ClauseTier.CORE
            lbd <= midThreshold -> ClauseTier.MID
            else -> ClauseTier.LOCAL
        }
        if (session.learnedClausePermanent(index)) {
            session.setLearnedClauseTier(index, tier)
            continue
        }
        when (tier) {
            ClauseTier.CORE -> session.setLearnedClauseTier(index, ClauseTier.CORE)

            ClauseTier.MID -> session.setLearnedClauseTier(index, if (used) ClauseTier.MID else ClauseTier.LOCAL)

            ClauseTier.LOCAL -> if (used) {
                session.setLearnedClauseTier(index, ClauseTier.MID)
            } else {
                session.setLearnedClauseTier(index, ClauseTier.LOCAL)
                locals.add(intArrayOf(lbd, index))
            }

            ClauseTier.UNSET -> error("tier was resolved")
        }
    }
    val residual = (cap - (learnedSize - locals.size)).coerceAtLeast(0)
    if (locals.size <= residual) {
        for (index in 0 until learnedSize) session.clearLearnedClauseUsed(index)
        return
    }
    locals.sortBy { it[0] }
    val dropped = IntHashSet(locals.size - residual)
    for (index in residual until locals.size) dropped.add(locals[index][1])
    session.forgetLearnedClauses { index, _ -> index !in dropped }
    params.onEvent?.invoke(SearchEvent.LearnedDbSweep(kept = learnedSize - dropped.size, dropped = dropped.size))
    for (index in 0 until session.learnedClauseCount) session.clearLearnedClauseUsed(index)
}

/** Strengthen a bounded round-robin slice of learned Boolean clauses at a root restart. */
internal fun vivify(session: PropagationSession, params: BacktrackParams, startCursor: Int): Int {
    val count = session.learnedClauseCount
    if (count == 0) return 0
    val native = session.usesNativeSat
    val numBool = session.problem.numBoolVars
    val replacements = ArrayList<IntArray>()
    val replacementLbds = IntArrayList()
    val dropped = IntHashSet()
    var cursor = if (startCursor in 0 until count) startCursor else 0
    repeat(minOf(params.vivifyBatch.coerceAtLeast(1), count)) {
        val index = cursor
        cursor = (cursor + 1) % count
        if (session.learnedClausePermanent(index) || !session.isLearnedClause(index)) return@repeat
        if (!native && !session.learnedClauseAt(index).allLiteralsBool(numBool)) return@repeat
        val literals = session.learnedClauseLiterals(index)
        if (literals.size < 3) return@repeat
        val strengthened = vivifyClause(session, literals) ?: return@repeat
        if (strengthened.size in 2 until literals.size) {
            dropped.add(index)
            replacements += strengthened
            replacementLbds.add(minOf(session.learnedClauseLbd(index), strengthened.size))
        }
    }
    if (replacements.isEmpty()) return cursor
    session.forgetLearnedClauses { index, _ -> index !in dropped }
    for (index in replacements.indices) session.addLearnedClause(Clause(replacements[index]), replacementLbds[index])
    return 0
}

private fun vivifyClause(session: PropagationSession, literals: IntArray): IntArray? {
    val kept = IntArrayList(literals.size)
    var pushes = 0
    var result: IntArray? = null
    for (literal in literals) {
        when (session.litTruth(literal)) {
            true -> {
                kept.add(literal)
                result = kept.toIntArray()
                break
            }

            false -> Unit

            null -> {
                kept.add(literal)
                if (session.pinBool(Lit.variable(literal), !Lit.isPositive(literal)) is PropagationResult.Unsat) {
                    result = kept.toIntArray()
                    break
                }
                pushes++
            }
        }
    }
    repeat(pushes) { session.popLast() }
    return result ?: kept.toIntArray().takeIf { it.size < literals.size }
}

internal fun snapshotAssignment(session: PropagationSession): Sample = Sample(
    BooleanArray(session.problem.numBoolVars) { session.boolValue(it) ?: false },
    LongArray(session.problem.numIntVars) { session.intDomain(it).min },
)

internal fun BacktrackSolver.farEnough(candidate: Sample, window: ArrayDeque<Sample>, minDistance: Int): Boolean =
    minDistance <= 0 || window.all { candidate.hammingDistanceTo(it) >= minDistance }
