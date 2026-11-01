package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.OWN_ORIGIN
import com.eignex.klause.propagation.PropagationSession

internal class PrivateRebuildBinaryLearning private constructor(
    val source: BakedProblem,
    val key: Long,
    val clauses: Array<Clause>,
) {
    companion object {
        const val SCRATCH_BYTES: Long = 16_384L
        const val MAX_RELATIONS: Int = 32
        const val SCAN_WORK: Long = 32_768L
        private const val MAX_BINARIES = 128
        private const val TABLE_BITS = 9
        private const val TABLE_SIZE = 1 shl TABLE_BITS
        private const val EMPTY_KEY = -1L
        private const val HASH_MULTIPLIER = -7046029254386353131L
        private const val COMPLEMENT_PAIR = (1L shl Int.SIZE_BITS) or 1L

        fun capture(
            session: PropagationSession,
            attempted: LongArray,
            attemptedCount: Int,
            visit: (Long) -> Boolean,
        ): PrivateRebuildBinaryLearning? {
            if (!session.canSimplifyRoot) return null
            val source = session.problem as? BakedProblem ?: return null
            if (!visit(TABLE_SIZE.toLong())) return null
            val previous = LongArray(TABLE_SIZE) { EMPTY_KEY }
            var eligible = 0
            var recorded = false
            for (index in session.learnedClauseCount - 1 downTo 0) {
                if (eligible == MAX_BINARIES) break
                if (!visit(5L)) return null
                if (!session.isLearnedClause(index) || session.learnedClausePermanent(index) ||
                    session.learnedClauseOrigin(index) != OWN_ORIGIN ||
                    session.learnedClauseLiteralCount(index) != 2) continue
                if (!visit(8L)) return null
                val literals = session.learnedClauseLiterals(index)
                val a = minOf(literals[0], literals[1])
                val b = maxOf(literals[0], literals[1])
                if (Lit.variable(a) !in 0 until source.numBoolVars ||
                    Lit.variable(b) !in 0 until source.numBoolVars || Lit.variable(a) == Lit.variable(b) ||
                    Lit.isPositive(a) == Lit.isPositive(b) ||
                    session.boolValue(Lit.variable(a)) != null || session.boolValue(Lit.variable(b)) != null) continue
                eligible++
                val key = pair(a, b)
                val opposite = key xor COMPLEMENT_PAIR
                val relation = minOf(key, opposite)
                var claimed = false
                for (entry in 0 until attemptedCount) {
                    if (!visit(1L)) return null
                    if (attempted[entry] == relation) { claimed = true; break }
                }
                if (claimed) continue
                val oppositeSlot = slot(previous, opposite, visit) ?: return null
                if (previous[oppositeSlot] == opposite) {
                    if (!visit(18L)) return null
                    return fromKey(source, key)
                }
                val keySlot = slot(previous, key, visit) ?: return null
                previous[keySlot] = key
                recorded = true
            }
            if (!recorded) return null
            for (factor in source.factors) {
                if (!visit(1L)) return null
                if (factor !is Clause || factor.literals.size != 2) continue
                if (!visit(2L)) return null
                val left = minOf(factor.literals[0], factor.literals[1])
                val right = maxOf(factor.literals[0], factor.literals[1])
                val key = pair(left, right) xor COMPLEMENT_PAIR
                if (key < 0L) continue
                val keySlot = slot(previous, key, visit) ?: return null
                if (previous[keySlot] == key) {
                    if (!visit(18L)) return null
                    return fromKey(source, key)
                }
            }
            return null
        }

        private fun slot(keys: LongArray, key: Long, visit: (Long) -> Boolean): Int? {
            var index = (((key xor (key ushr Int.SIZE_BITS)) * HASH_MULTIPLIER) ushr
                (Long.SIZE_BITS - TABLE_BITS)).toInt()
            while (true) {
                if (!visit(1L)) return null
                if (keys[index] == EMPTY_KEY || keys[index] == key) return index
                index = (index + 1) and (TABLE_SIZE - 1)
            }
        }

        private fun fromKey(source: BakedProblem, key: Long): PrivateRebuildBinaryLearning {
            val a = (key ushr Int.SIZE_BITS).toInt()
            val b = key.toInt()
            return PrivateRebuildBinaryLearning(source, minOf(key, key xor COMPLEMENT_PAIR), arrayOf(
                Clause(intArrayOf(a, b)), Clause(intArrayOf(Lit.negate(a), Lit.negate(b))),
            ))
        }

        private fun pair(a: Int, b: Int): Long =
            (a.toLong() shl Int.SIZE_BITS) or (b.toLong() and 0xFFFF_FFFFL)
    }
}
