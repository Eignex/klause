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
        private const val COMPLEMENT_PAIR = (1L shl Int.SIZE_BITS) or 1L

        fun capture(
            session: PropagationSession,
            attempted: LongArray,
            attemptedCount: Int,
            visit: (Long) -> Boolean,
        ): PrivateRebuildBinaryLearning? {
            if (!session.canSimplifyRoot) return null
            val source = session.problem as? BakedProblem ?: return null
            if (!visit(MAX_BINARIES.toLong())) return null
            val previous = LongArray(MAX_BINARIES)
            var count = 0
            var eligible = 0
            for (index in 0 until session.learnedClauseCount) {
                if (eligible == MAX_BINARIES) return null
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
                var found = false
                for (entry in 0 until count) {
                    if (!visit(1L)) return null
                    if (previous[entry] == opposite) { found = true; break }
                }
                if (!found) for (factor in source.factors) {
                    if (!visit(1L)) return null
                    if (factor !is Clause || factor.literals.size != 2) continue
                    if (!visit(2L)) return null
                    val left = minOf(factor.literals[0], factor.literals[1])
                    val right = maxOf(factor.literals[0], factor.literals[1])
                    if (pair(left, right) == opposite) { found = true; break }
                }
                if (found) {
                    if (!visit(18L)) return null
                    return PrivateRebuildBinaryLearning(source, relation, arrayOf(
                        Clause(intArrayOf(a, b)), Clause(intArrayOf(Lit.negate(a), Lit.negate(b))),
                    ))
                }
                if (count == previous.size) return null
                previous[count++] = key
            }
            return null
        }

        private fun pair(a: Int, b: Int): Long =
            (a.toLong() shl Int.SIZE_BITS) or (b.toLong() and 0xFFFF_FFFFL)
    }
}
