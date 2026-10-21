package com.eignex.klause.presolve

import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

internal object OriginalClauseSubsumption {
    private const val MAX_WIDTH = 8
    private const val MAX_FACTORS = 200_000
    private const val MAX_BOOL_VARS = 200_000
    private const val MAX_WORK = 1_000_000L
    private const val NO_FLIP = -1

    internal class Limits(val candidates: Long = 100_000L, val literals: Long = 1_000_000L)

    fun eligible(problem: Problem): Boolean = problem.numBoolVars in 1..MAX_BOOL_VARS &&
        problem.factors.size <= MAX_FACTORS

    private class Work(private val cancellation: Cancellation, private val limits: Limits) {
        private val meter = cancellation.workMeter()
        private var visits = 0L
        private var candidates = 0L
        private var literals = 0L
        private var stopped = false

        fun visit(): Boolean {
            if (stopped) return false
            if (visits >= MAX_WORK || ((meter != null || visits and 63L == 0L) && cancellation())) {
                stop()
                return false
            }
            visits++
            meter?.charge(1L)
            return true
        }

        fun candidate(): Boolean {
            if (candidates >= limits.candidates) {
                stop()
                return false
            }
            if (!visit()) return false
            candidates++
            return true
        }

        fun literal(): Boolean {
            if (literals >= limits.literals) {
                stop()
                return false
            }
            if (!visit()) return false
            literals++
            return true
        }

        fun active(): Boolean = !stopped && !cancellation()

        private fun stop() {
            stopped = true
            cancellation.presolveBudget()?.activeInvocation?.interrupted = true
        }
    }

    fun simplify(
        problem: Problem,
        cancellation: Cancellation = Cancellation.Never,
        limits: Limits = Limits(),
    ): SourceDelta {
        if (cancellation() || !eligible(problem)) {
            return SourceDelta()
        }
        val work = Work(cancellation, limits)
        val db = SatClauseDb.buildShortClauses(problem, MAX_WIDTH, work::visit) ?: return SourceDelta()
        val queue = IntArrayList()
        val signatures = ArrayList<Long>()
        for (slot in 0 until db.slotCount) {
            if (!work.visit()) return SourceDelta()
            queue.add(slot)
            signatures.add(signature(db.clause(slot)!!))
        }
        var cursor = 0
        while (cursor < queue.size && work.active()) {
            if (!work.visit()) break
            val slot = queue[cursor++]
            val clause = db.clause(slot) ?: continue
            val seen = IntHashSet()
            scan@ for (literal in clause) {
                for (polarity in 0..1) {
                    val occurrences = db.occ(literal xor polarity)
                    // Appended replacements have their own queue entry.
                    val size = occurrences.size
                    for (i in 0 until size) {
                        if (!work.candidate()) return db.toSourceDelta(SourceRebuilds.NONE)
                        val other = occurrences[i]
                        if (other == slot || !seen.add(other)) continue
                        val peer = db.clause(other) ?: continue
                        val forward = match(clause, peer, signatures[slot], signatures[other], work)
                        if (forward != null && work.active()) {
                            if (peer.size == 1 && forward != NO_FLIP) return SourceDelta(infeasible = true)
                            replace(other, peer, forward, db, queue, signatures)
                            continue
                        }
                        val reverse = match(peer, clause, signatures[other], signatures[slot], work)
                        if (reverse != null && work.active()) {
                            if (clause.size == 1 && reverse != NO_FLIP) return SourceDelta(infeasible = true)
                            replace(slot, clause, reverse, db, queue, signatures)
                            break@scan
                        }
                    }
                }
            }
        }
        return db.toSourceDelta(SourceRebuilds.NONE)
    }

    private fun match(premise: IntArray, target: IntArray, premiseSig: Long, targetSig: Long, work: Work): Int? {
        if (premise.size > target.size || premiseSig and targetSig.inv() != 0L) return null
        var flipped = NO_FLIP
        for (literal in premise) {
            var found = false
            for (candidate in target) {
                if (!work.literal()) return null
                if (candidate == literal) {
                    found = true
                    break
                }
                if (candidate == Lit.negate(literal) && flipped == NO_FLIP) {
                    found = true
                    flipped = candidate
                    break
                }
            }
            if (!found) return null
        }
        return flipped
    }

    private fun replace(
        slot: Int,
        target: IntArray,
        flipped: Int,
        db: SatClauseDb,
        queue: IntArrayList,
        signatures: ArrayList<Long>,
    ) {
        // Every edit is equivalent against the live database. Publishing its replacement before
        // retiring the target keeps earlier premises implied, even when they are rewritten later.
        if (flipped != NO_FLIP) {
            val shortened = IntArray(target.size - 1)
            var next = 0
            for (literal in target) if (literal != flipped) shortened[next++] = literal
            queue.add(db.add(shortened))
            signatures.add(signature(shortened))
        }
        db.remove(slot)
    }

    private fun signature(literals: IntArray): Long {
        var signature = 0L
        for (literal in literals) signature = signature or (1L shl (Lit.variable(literal) * 0x9E3779B1.toInt() ushr 26))
        return signature
    }
}
