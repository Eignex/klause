package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.presolve.RebuildStep
import com.eignex.klause.presolve.SourceRebuilds
import com.eignex.klause.propagation.OWN_ORIGIN
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.util.Cancellation

internal class PrivateRebuildWarmClauses private constructor(
    val clauses: Array<Factor>,
    val literals: Long,
    val modeledBytes: Long,
    val work: Long,
) {
    companion object {
        fun capture(
            session: PropagationSession,
            rebuild: SourceRebuilds,
            fixed: Array<Boolean?>,
            byteLimit: Long,
            allowance: Long,
            cancellation: Cancellation,
        ): PrivateRebuildWarmClauses? {
            if (!session.canSimplifyRoot || cancellation()) return null
            var spent = 0L
            fun visit(work: Long): Boolean {
                if (work > allowance - spent) return false
                spent += work
                cancellation.charge(work)
                return !cancellation()
            }
            val clauses = ArrayList<Factor>()
            var literals = 0L
            var bytes = 0L
            fun result(): PrivateRebuildWarmClauses? = if (cancellation()) null else {
                PrivateRebuildWarmClauses(clauses.toTypedArray(), literals, bytes, spent)
            }
            if (!visit(fixed.size * 2L + rebuild.steps.size)) return result()
            val eliminated = BooleanArray(fixed.size)
            for (step in rebuild.steps) {
                if (step !is RebuildStep.SatisfyClauses) return null
                eliminated[step.variable] = true
            }
            for (index in 0 until session.learnedClauseCount) {
                if (!visit(5L)) break
                if (!session.isLearnedClause(index) || session.learnedClausePermanent(index) ||
                    session.learnedClauseOrigin(index) != OWN_ORIGIN) continue
                val size = session.learnedClauseLiteralCount(index)
                if ((size + 1L) * 512L > byteLimit - bytes) continue
                if (!visit(size * 3L + 1L)) break
                val source = session.learnedClauseLiterals(index)
                // Only consequences over the retained namespace follow the existential projection
                // without resolving the learned clause through eliminated columns.
                if (source.any { Lit.variable(it) >= fixed.size || eliminated[Lit.variable(it)] }) continue
                if (source.any { fixed[Lit.variable(it)]?.let { value -> Lit.evaluate(it, value) } == true }) continue
                val residual = source.filter { fixed[Lit.variable(it)] == null }.toIntArray()
                if (residual.isEmpty()) return null
                clauses.add(Clause(residual))
                literals += residual.size
                bytes += (residual.size + 1L) * 512L
            }
            return result()
        }
    }
}
